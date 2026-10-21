package com.tipdm.agentplatform.service.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.AlertChannel;
import com.tipdm.agentplatform.domain.ChannelCredential;
import com.tipdm.agentplatform.domain.PlatformNotification;
import com.tipdm.agentplatform.repository.AlertChannelRepository;
import com.tipdm.agentplatform.repository.ChannelCredentialRepository;
import com.tipdm.agentplatform.service.ExternalApiKeyCipher;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import com.tipdm.agentplatform.util.OutboundUrlGuard;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 告警多渠道扇出：把一条告警发到告警归属人的全部启用渠道。
 *
 * <p>与 {@link AlertNotifier} 的关系：AlertNotifier 是告警出口门面（落库 + SSE +
 * 全局企微 webhook），本类是其第四条路径——<b>用户自建渠道</b>。全局企微 webhook
 * 面向运维统一值守群；本类面向「告警归属人自己配的钉钉群 / 飞书群」，两者并存、
 * 互不替代。</p>
 *
 * <p>payload 形态（每种渠道一条腿）：</p>
 * <ul>
 *   <li>wechat_work：{@code {msgtype:"markdown", markdown:{content}}}（同全局渠道）</li>
 *   <li>dingtalk：{@code {msgtype:"markdown", markdown:{title,text}}}；secret 存在时
 *       {@code hmac_sha256(secret, "timestamp\nsecret")} → base64 → urlencode，
 *       追加 {@code &timestamp=&sign=} 查询参数</li>
 *   <li>feishu：{@code {msg_type:"text", content:{text}, timestamp, sign}}；签名是
 *       {@code base64(hmac_sha256(key="timestamp\nsecret", message=""))}</li>
 *   <li>webhook：原始告警 JSON（level/title/body/sessionId/agentId/createdAt）；
 *       secret 存在时带 {@code Authorization: Bearer} 头</li>
 * </ul>
 *
 * <p>投递结果落在渠道行上（{@code status} / {@code last_error} / {@code last_sent_at}），
 * 供渠道列表的状态徽标展示——webhook 型渠道没有常驻连接，「连通」只能由最近一次
 * 真实投递证明。</p>
 */
@Service
public class AlertChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertChannelDispatcher.class);

    private static final int CONTENT_LIMIT = 3500;
    private static final int TIMEOUT_SECONDS = 10;

    /** 连通性测试（在 HTTP 请求线程里同步等结果）的总时限。 */
    private static final int TEST_TIMEOUT_SECONDS = 6;

    private final AlertChannelRepository repository;
    private final ChannelCredentialRepository credentialRepository;
    private final ChannelTypeRegistry registry;
    private final ObjectMapper objectMapper;
    private final OutboundUrlGuard urlGuard;
    private final ExternalApiKeyCipher cipher;
    private final HttpClient httpClient;

    /**
     * 渠道外发专用线程池。
     *
     * <p>JDK {@code HttpClient} 不传 executor 时用内部公共池：既无法限流，也无法在
     * 关机时关闭（线程都活着）。这里显式给一个 4 线程池，随应用生命周期启停。</p>
     */
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "alert-channel-http");
        thread.setDaemon(true);
        return thread;
    });

    /** 失败重试调度器（单线程）。抖动/5xx 是暂态，一次失败不该永久丢告警。 */
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "alert-channel-retry");
                thread.setDaemon(true);
                return thread;
            });

    /** 最多尝试次数（含首次）。 */
    private static final int MAX_SEND_ATTEMPTS = 3;

    /** 重试间隔（秒）：5s、15s，避免持续打一个正在抖动的对端。 */
    private static final long[] RETRY_DELAY_SECONDS = {5, 15};

    @jakarta.annotation.PreDestroy
    void shutdownExecutors() {
        retryScheduler.shutdown();
        httpExecutor.shutdown();
    }

    public AlertChannelDispatcher(AlertChannelRepository repository,
                                  ChannelCredentialRepository credentialRepository,
                                  ChannelTypeRegistry registry,
                                  ObjectMapper objectMapper,
                                  OutboundUrlGuard urlGuard,
                                  ExternalApiKeyCipher cipher) {
        this.repository = repository;
        this.credentialRepository = credentialRepository;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.urlGuard = urlGuard;
        this.cipher = cipher;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(httpExecutor)
                .build();
    }

    /**
     * 把告警扇出到归属人的全部启用渠道（各自过滤 min_level）。
     * 全部异步：任何一条失败只落渠道状态，不影响告警主流程。
     *
     * @return 本次实际发起外发的渠道数（便于日志与测试断言）
     */
    public int dispatch(PlatformNotification n) {
        return dispatch(n, null);
    }

    /**
     * 把告警扇出到归属人的启用渠道，可带渠道白名单。
     *
     * @param onlyChannelIds null = 不限（全部启用渠道）；非空 = 只发这些 id
     *                       的渠道（越权/已删除/已停用的 id 自然无匹配，无害）
     * @return 本次实际发起外发的渠道数
     */
    public int dispatch(PlatformNotification n, java.util.Collection<UUID> onlyChannelIds) {
        if (n == null || n.getUserId() == null || n.getUserId().isBlank()) {
            return 0;
        }
        List<AlertChannel> channels;
        try {
            channels = repository.findByUserIdAndEnabledTrue(n.getUserId());
        } catch (RuntimeException e) {
            log.warn("alert-channel: load channels failed for user {}: {}",
                    n.getUserId(), e.getMessage());
            return 0;
        }
        if (onlyChannelIds != null) {
            channels = channels.stream()
                    .filter(c -> onlyChannelIds.contains(c.getId()))
                    .toList();
            if (channels.isEmpty()) {
                return 0;
            }
        }
        int dispatched = 0;
        Map<UUID, Map<String, Object>> credsByChannel =
                loadCredentials(channels.stream().map(AlertChannel::getId).toList());
        for (AlertChannel channel : channels) {
            if (belowMinLevel(n.getLevel(), channel)) {
                continue;
            }
            dispatched++;
            sendAsync(channel, credsByChannel.getOrDefault(channel.getId(), Map.of()), n);
        }
        return dispatched;
    }

    /**
     * 发送测试消息（渠道保存后的连通性验证）。同步执行，直接把结果给调用方。
     */
    public Map<String, Object> sendTest(AlertChannel channel) {
        Map<String, Object> credentials =
                loadCredentials(List.of(channel.getId())).getOrDefault(channel.getId(), Map.of());
        PlatformNotification sample = new PlatformNotification();
        sample.setUserId(channel.getUserId());
        sample.setLevel("info");
        sample.setTitle("渠道测试消息");
        sample.setBody("这是一条来自 AgentHub 的测试告警。收到即表示渠道配置可用。");
        DispatchResult result;
        try {
            // sendAsync + 有界 get：send() 的最坏耗时由「连接超时 + 读超时」叠加决定
            // （连接 5s + 读 10s），而 DNS 解析还不在任何超时之内；这里再兜一层总时限，
            // 把 Tomcat 请求线程的占用钉死在 TEST_TIMEOUT_SECONDS 内。
            HttpResponse<String> resp = httpClient.sendAsync(
                            buildRequest(channel, credentials, sample),
                            HttpResponse.BodyHandlers.ofString())
                    .get(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (resp.statusCode() >= 400) {
                // 响应体可能来自被 SSRF 命中的内网服务，只进日志、不回显给调用方
                log.warn("alert-channel: {} test failed status={} body={}",
                        channel.getId(), resp.statusCode(), clip(resp.body(), 200));
                result = DispatchResult.fail("HTTP " + resp.statusCode());
            } else {
                result = DispatchResult.success();
            }
        } catch (ApiException e) {
            // 地址未通过出网守卫——属用户自身配置问题，提示可以直接返回
            result = DispatchResult.fail(e.getMessage());
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("alert-channel: {} test timed out after {}s", channel.getId(), TEST_TIMEOUT_SECONDS);
            result = DispatchResult.fail("发送超时（" + TEST_TIMEOUT_SECONDS + " 秒），请检查渠道地址可达性");
        } catch (Exception e) {
            // 异常信息会带出目标地址（如 Connection to http://10.x.x.x refused），只进日志
            log.warn("alert-channel: {} test error: {}", channel.getId(), describe(e));
            result = DispatchResult.fail("发送失败，请检查渠道地址与网络连通性");
        }
        recordResult(channel, result);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.ok());
        if (!result.ok()) {
            body.put("error", result.error());
        }
        return body;
    }

    /* ─────────────────────────── 分类型发送 ─────────────────────────── */

    private void sendAsync(AlertChannel channel, Map<String, Object> credentials, PlatformNotification n) {
        final HttpRequest request;
        try {
            request = buildRequest(channel, credentials, n);
        } catch (ApiException e) {
            // 地址未通过出网守卫等——提示对用户有用，可以直接落状态
            recordResult(channel, DispatchResult.fail(e.getMessage()));
            return;
        } catch (RuntimeException e) {
            // 构造失败（URL 非法 / 凭据缺失等）也要落状态，不能让扇出线程带异常逃逸
            log.warn("alert-channel: {} build request failed: {}", channel.getId(), e.getMessage());
            recordResult(channel, DispatchResult.fail("渠道请求构造失败"));
            return;
        }
        attemptSend(channel, credentials, n, request, 1);
    }

    /**
     * 发送并在暂态失败时重试。
     *
     * <p>原实现一次失败即永久丢这条告警（渠道状态落 failed，但消息不会重发）；而
     * 抖动（连接被拒 / 读超时）与 5xx 都是暂态。<b>4xx 与签名错误不重试</b>——那是
     * 配置问题，重试只会反复刷失败。重试复用同一个 {@link HttpRequest}（不可变对象），
     * 加签时间戳在秒级内仍然有效。</p>
     *
     * @param attempt 第几次尝试（从 1 开始）
     */
    private void attemptSend(AlertChannel channel, Map<String, Object> credentials,
                             PlatformNotification n, HttpRequest request, int attempt) {
        // HttpRequest.timeout(10s) 已覆盖「连接 + 读取」全程，原先叠加的
        // orTimeout(20s) 永远不会先触发，属冗余常量，已移除。
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, ex) -> {
                    DispatchResult result;
                    if (ex != null) {
                        // 异常信息带目标地址，只进日志
                        log.warn("alert-channel: {} send error (attempt {}/{}): {}",
                                channel.getId(), attempt, MAX_SEND_ATTEMPTS, describe(ex));
                        if (attempt < MAX_SEND_ATTEMPTS) {
                            scheduleRetry(channel, credentials, n, attempt);
                            return;
                        }
                        result = DispatchResult.fail("发送失败，请检查渠道地址与网络连通性");
                    } else if (resp.statusCode() >= 400) {
                        // last_error 会展示在渠道列表状态徽标上，响应体只进日志
                        log.warn("alert-channel: {} send failed status={} body={} (attempt {}/{})",
                                channel.getId(), resp.statusCode(), clip(resp.body(), 200),
                                attempt, MAX_SEND_ATTEMPTS);
                        if (resp.statusCode() >= 500 && attempt < MAX_SEND_ATTEMPTS) {
                            scheduleRetry(channel, credentials, n, attempt);
                            return;
                        }
                        result = DispatchResult.fail("HTTP " + resp.statusCode());
                    } else {
                        result = DispatchResult.success();
                    }
                    recordResult(channel, result);
                });
    }

    /** 按 RETRY_DELAY_SECONDS 异步排一次重试；调度失败则直接落失败状态，不丢结果。 */
    private void scheduleRetry(AlertChannel channel, Map<String, Object> credentials,
                               PlatformNotification n, int attempt) {
        long delay = RETRY_DELAY_SECONDS[Math.min(attempt - 1, RETRY_DELAY_SECONDS.length - 1)];
        HttpRequest request;
        try {
            request = buildRequest(channel, credentials, n);
        } catch (RuntimeException e) {
            recordResult(channel, DispatchResult.fail("渠道请求构造失败"));
            return;
        }
        try {
            retryScheduler.schedule(
                    () -> attemptSend(channel, credentials, n, request, attempt + 1),
                    delay, java.util.concurrent.TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            // 调度器已关闭（应用关机中）：落一次失败状态，不静默丢弃
            recordResult(channel, DispatchResult.fail("发送失败（重试调度不可用）"));
        }
    }

    private HttpRequest buildRequest(AlertChannel channel, Map<String, Object> credentials,
                                     PlatformNotification n) {
        String type = channel.getChannelType();
        String url;
        String jsonBody;
        switch (type == null ? "" : type) {
            case ChannelTypeRegistry.TYPE_DINGTALK -> {
                url = (String) credentials.get("webhook_url");
                long timestamp = System.currentTimeMillis();
                String secret = str(credentials.get("secret"));
                if (!secret.isBlank()) {
                    String sign = urlEncode(dingtalkSign(secret, timestamp));
                    url = url + (url.contains("?") ? "&" : "?")
                            + "timestamp=" + timestamp + "&sign=" + sign;
                }
                Map<String, Object> markdown = new LinkedHashMap<>();
                markdown.put("title", n.getTitle());
                markdown.put("text", renderMarkdown(n));
                jsonBody = json(Map.of("msgtype", "markdown", "markdown", markdown));
            }
            case ChannelTypeRegistry.TYPE_FEISHU -> {
                url = (String) credentials.get("webhook_url");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("msg_type", "text");
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("text", renderText(n));
                body.put("content", content);
                String secret = str(credentials.get("secret"));
                if (!secret.isBlank()) {
                    long timestamp = System.currentTimeMillis() / 1000;
                    body.put("timestamp", String.valueOf(timestamp));
                    body.put("sign", feishuSign(secret, timestamp));
                }
                jsonBody = json(body);
            }
            case ChannelTypeRegistry.TYPE_WEBHOOK -> {
                url = (String) credentials.get("url");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("level", n.getLevel());
                payload.put("title", n.getTitle());
                payload.put("body", n.getBody());
                payload.put("sessionId", n.getSessionId());
                payload.put("agentId", n.getAgentId());
                payload.put("createdAt", n.getCreatedAt() == null
                        ? Instant.now().toString() : n.getCreatedAt().toString());
                jsonBody = json(payload);
            }
            case ChannelTypeRegistry.TYPE_WECHAT_WORK -> {
                url = (String) credentials.get("webhook_url");
                jsonBody = json(Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of("content", renderMarkdown(n))));
            }
            default -> throw new IllegalArgumentException("不支持的渠道类型: " + type);
        }
        // SSRF 防护：渠道地址（webhook_url / endpoint）由用户自由填写，发请求前必须校验，
        // 否则「保存渠道 → 点测试」就是一个现成的内网探测入口。
        urlGuard.requireSafe(url, "告警渠道地址 ");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        String token = str(credentials.get("secret"));
        if (ChannelTypeRegistry.TYPE_WEBHOOK.equals(type) && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    /* ─────────────────────────── 结果落库 ─────────────────────────── */

    private void recordResult(AlertChannel channel, DispatchResult result) {
        try {
            AlertChannel managed = repository.findById(channel.getId()).orElse(null);
            if (managed == null) {
                return; // 渠道已被删除，结果无处落
            }
            managed.setLastSentAt(Instant.now());
            managed.setLastError(result.ok() ? null : result.error());
            managed.setStatus(result.ok()
                    ? (managed.isEnabled() ? "connected" : "stopped")
                    : "failed");
            repository.save(managed);
        } catch (RuntimeException e) {
            log.debug("alert-channel: record result failed for {}: {}",
                    channel.getId(), e.getMessage());
        }
    }

    private boolean belowMinLevel(String level, AlertChannel channel) {
        String minLevel = registry.normalizeMinLevel(channel.getMinLevel());
        return rank(level) < rank(minLevel);
    }

    /** 批量取渠道凭据并按渠道分组（key → value），避免逐渠道 N+1。 */
    private Map<UUID, Map<String, Object>> loadCredentials(List<UUID> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, Object>> result = new java.util.HashMap<>();
        try {
            for (ChannelCredential row : credentialRepository.findByChannelIdIn(channelIds)) {
                result.computeIfAbsent(row.getChannelId(), k -> new LinkedHashMap<>())
                        .put(row.getCredKey(), decodeCredential(row.getCredValue()));
            }
        } catch (RuntimeException e) {
            log.warn("alert-channel: load credentials failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 还原凭据明文：正常走 AES-GCM 解密；解密失败则判定为**存量明文**原样返回。
     *
     * <p>加密存储上线前库里已存在明文凭据，这里必须兼容，否则存量渠道会全部签名失败。
     * 明文渠道在下一次保存（整体覆盖写）时自动转为密文。</p>
     */
    private String decodeCredential(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        try {
            return cipher.decrypt(stored);
        } catch (RuntimeException e) {
            log.debug("alert-channel: credential stored in plaintext, using as-is: {}", e.getMessage());
            return stored;
        }
    }

    /* ─────────────────────────── 签名与渲染 ─────────────────────────── */

    /** 钉钉加签：HmacSHA256(key=secret, data="timestamp\nsecret") → base64。 */
    static String dingtalkSign(String secret, long timestamp) {
        String stringToSign = timestamp + "\n" + secret;
        return hmacBase64(secret, stringToSign);
    }

    /** 飞书签名：HmacSHA256(key="timestamp\nsecret", data="") → base64。 */
    static String feishuSign(String secret, long timestamp) {
        String key = timestamp + "\n" + secret;
        return hmacBase64(key, "");
    }

    private static String hmacBase64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 签名失败: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String renderMarkdown(PlatformNotification n) {
        StringBuilder sb = new StringBuilder();
        sb.append("**[").append(n.getLevel().toUpperCase()).append("] ")
                .append(n.getTitle()).append("**\n");
        if (notBlank(n.getBody())) {
            sb.append("\n").append(n.getBody()).append("\n");
        }
        if (notBlank(n.getSessionId())) {
            sb.append("\n> 会话：`").append(n.getSessionId()).append("`\n");
        }
        sb.append("> 时间：").append(n.getCreatedAt() == null ? Instant.now() : n.getCreatedAt());
        return clip(sb.toString(), CONTENT_LIMIT);
    }

    private String renderText(PlatformNotification n) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(n.getLevel().toUpperCase()).append("] ")
                .append(n.getTitle()).append("\n");
        if (notBlank(n.getBody())) {
            sb.append("\n").append(n.getBody()).append("\n");
        }
        if (notBlank(n.getSessionId())) {
            sb.append("\n会话：").append(n.getSessionId()).append("\n");
        }
        sb.append("\n时间：").append(n.getCreatedAt() == null ? Instant.now() : n.getCreatedAt());
        return clip(sb.toString(), CONTENT_LIMIT);
    }

    /* ─────────────────────────── 工具 ─────────────────────────── */

    private static int rank(String level) {
        if (AlertNotifier.LEVEL_ERROR.equalsIgnoreCase(level)) {
            return 3;
        }
        if (AlertNotifier.LEVEL_WARN.equalsIgnoreCase(level)) {
            return 2;
        }
        return 1;
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("payload 序列化失败", e);
        }
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 异常描述兜底：部分异常（如本机端口拒绝）getMessage() 为 null，落库存类名。 */
    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    record DispatchResult(boolean ok, String error) {
        static DispatchResult success() {
            return new DispatchResult(true, null);
        }

        static DispatchResult fail(String error) {
            return new DispatchResult(false, error);
        }
    }
}
