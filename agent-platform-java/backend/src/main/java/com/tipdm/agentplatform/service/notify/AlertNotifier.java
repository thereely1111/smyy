package com.tipdm.agentplatform.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.AlertProperties;
import com.tipdm.agentplatform.domain.PlatformNotification;
import com.tipdm.agentplatform.repository.PlatformNotificationRepository;
import com.tipdm.agentplatform.service.channel.AlertChannelDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 告警发布门面：把「服务端知道、用户不知道」的事件主动推给人。
 *
 * <p><b>为什么需要它</b>：会话停摆 / run 静默判死 / HITL 长期未决这类事件此前只落在
 * 服务日志里，服务端明确知道「它停了」，用户却毫无感知——教材会话曾静默空转 3.5 小时
 * 无人发现（2026-09-16 实证）。本类是「知道」到「告知」之间的唯一出口。</p>
 *
 * <p><b>三条投递路径</b>（全部尽力而为，任一失败不影响主流程）：</p>
 * <ol>
 *   <li>落库 {@code platform_notification}：保证离线不丢，进站铃铛红点；</li>
 *   <li>SSE {@code session-alert}：经 {@link NotifyStreamRegistry} 推给在线用户，
 *       前端弹 Toast 并刷新未读角标；</li>
 *   <li>企业微信机器人 webhook：达到配置级别时异步外发，覆盖人不在页面前的场景；</li>
 *   <li>用户自建渠道扇出（{@code AlertChannelDispatcher}）：钉钉/飞书/企微/通用
 *       webhook，按告警归属人启用渠道外发，覆盖多 IM 场景。</li>
 * </ol>
 *
 * <p><b>去重</b>：周期性看护（60s 一轮）会对同一停摆会话反复命中，因此告警必须带
 * {@code alertKey}——相同 key 在冷却窗口内被抑制（查库按 createdAt 判断，重启不失效）。
 * 无 key 的告警不去重，调用方需自行保证低频。</p>
 */
@Service
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    /** SSE 事件名：与前端 useNotifyStream 监听的 addEventListener 名称一致。 */
    public static final String EVENT_SESSION_ALERT = "session-alert";

    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_ERROR = "error";

    private static final int WECOM_CONTENT_LIMIT = 3500;

    private final PlatformNotificationRepository repository;
    private final NotifyStreamRegistry registry;
    private final AlertProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AlertChannelDispatcher channelDispatcher;
    private final SessionAlertPushService pushGate;

    /**
     * 渠道扇出专用线程池（1 线程、有界队列 200、满则回退调用方线程）。
     *
     * <p>扇出要做「白名单查询 + 渠道/凭据查询 + 逐渠道发送编排」，而 {@link #publish}
     * 由看护线程周期性调用（{@code SessionProgressService} / {@code RunSilenceDetector}），
     * 原先这些 DB 查询直接压在看护线程上，DB 抖动时整个看护变慢。队列有界 +
     * CallerRunsPolicy：极端堆积时宁可让告警线程自己跑，也不无界排队吃内存。</p>
     */
    private final ThreadPoolExecutor fanoutExecutor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            runnable -> {
                Thread thread = new Thread(runnable, "alert-fanout");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    @jakarta.annotation.PreDestroy
    void shutdownFanoutExecutor() {
        fanoutExecutor.shutdown();
    }

    /** 在跑的扇出任务数：与队列长度一起构成"扇出是否已排空"的判据（见 awaitFanOutIdle）。 */
    private final java.util.concurrent.atomic.AtomicInteger fanoutActive =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 等待异步扇出全部跑完（包可见，供测试使用）。
     *
     * <p>扇出已移出调用线程：测试若直接断言 {@code verify(..., never())}，会在任务尚未
     * 执行时"假通过"；阻塞等待队列排空才能得到确定结论。生产路径不调用它。</p>
     */
    boolean awaitFanOutIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (fanoutActive.get() == 0 && fanoutExecutor.getQueue().isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public AlertNotifier(PlatformNotificationRepository repository,
                         NotifyStreamRegistry registry,
                         AlertProperties props,
                         ObjectMapper objectMapper,
                         AlertChannelDispatcher channelDispatcher,
                         SessionAlertPushService pushGate) {
        this.repository = repository;
        this.registry = registry;
        this.props = props;
        this.objectMapper = objectMapper;
        this.channelDispatcher = channelDispatcher;
        this.pushGate = pushGate;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 发布一条告警。
     *
     * @param request 告警内容（userId 必填；anonymous/null 直接忽略）
     * @return 落库后的通知 id；被抑制或未启用返回 null
     */
    public UUID publish(AlertRequest request) {
        if (request == null || isBlank(request.userId()) || isAnonymous(request.userId())) {
            return null;
        }
        if (!props.isEnabled()) {
            log.info("alert: disabled, skip [{}] {}", request.level(), request.title());
            return null;
        }
        if (!isBlank(request.alertKey()) && isSuppressed(request)) {
            log.debug("alert: suppressed by cooldown (key={}, title={})", request.alertKey(), request.title());
            return null;
        }
        PlatformNotification saved;
        try {
            saved = persist(request);
        } catch (RuntimeException e) {
            // 落库失败不应影响业务主流程（看护/判死仍在继续），但必须留下痕迹
            log.error("alert: persist failed for [{}] {}: {}", request.level(), request.title(), e.getMessage());
            return null;
        }
        pushStream(saved);
        maybePushWecom(saved);
        // 第四条路径：扇出到告警归属人自建的渠道（钉钉/飞书/企微/通用 webhook）。
        // 会话告警按用户在面板里勾选的白名单外发（opt-in）：无记录/没勾渠道不外发；
        // 全局告警（sessionId 为空，resolveFanoutChannelIds 返回 null）不受白名单
        // 约束，按用户全部启用渠道扇出。整个扇出（含查询）丢独立线程池，
        // dispatch 内部同样是异步 + 吞异常。
        final PlatformNotification alert = saved;
        fanoutExecutor.execute(() -> {
            fanoutActive.incrementAndGet();
            try {
                List<UUID> fanoutChannels =
                        pushGate.resolveFanoutChannelIds(alert.getUserId(), alert.getSessionId());
                if (fanoutChannels != null && fanoutChannels.isEmpty()) {
                    log.debug("alert: channel fan-out skipped by session subscription (user={}, session={})",
                            alert.getUserId(), alert.getSessionId());
                    return;
                }
                int dispatched = channelDispatcher.dispatch(alert, fanoutChannels);
                if (dispatched > 0) {
                    log.info("alert: fanned out to {} user channel(s) (user={})",
                            dispatched, alert.getUserId());
                }
            } catch (RuntimeException e) {
                log.warn("alert: channel fan-out failed: {}", e.getMessage());
            } finally {
                fanoutActive.decrementAndGet();
            }
        });
        log.warn("alert: published [{}] {} (user={}, session={})",
                saved.getLevel(), saved.getTitle(), saved.getUserId(), saved.getSessionId());
        return saved.getId();
    }

    /**
     * 抑制判定：两层。
     * <ol>
     *   <li><b>未读抑制</b>（主）：同 key 已有<b>时效内</b>的未读告警 → 抑制。语义是
     *       「提醒一次，直到你看过」——周期看护 60s 一轮，只靠时间冷却一夜仍能刷出十几条。
     *       时效上限见 {@link AlertProperties#getUnreadSuppressMaxHours()}：用户始终不读时，
     *       超过上限降级为冷却判定，否则同一问题再也不会提醒（2026-09-17）。</li>
     *   <li><b>时间冷却</b>（辅）：用户读完立刻又被同一事件命中时的兜底。</li>
     * </ol>
     */
    private boolean isSuppressed(AlertRequest request) {
        try {
            Instant maxSuppressSince = Instant.now().minus(
                    Duration.ofHours(Math.max(props.getUnreadSuppressMaxHours(), 1)));
            if (repository.countUnreadSince(
                    request.userId(), request.alertKey(), maxSuppressSince) > 0) {
                return true;
            }
        } catch (RuntimeException e) {
            log.debug("alert: unread check failed (key={}): {}", request.alertKey(), e.getMessage());
        }
        Instant since = Instant.now().minus(Duration.ofMinutes(Math.max(props.getDedupCooldownMinutes(), 0)));
        try {
            return repository.findLatestAlertAt(request.userId(), request.alertKey(), since) != null;
        } catch (RuntimeException e) {
            log.debug("alert: dedup query failed (key={}): {}", request.alertKey(), e.getMessage());
            return false; // 查不动就不抑制，宁可重复也不要丢告警
        }
    }

    /**
     * 落库。不声明 {@code REQUIRES_NEW}：本方法是同类内部自调用，代理不生效，
     * 写了也是误导。{@code repository.save} 自带事务——调用方无事务则独立提交，
     * 有事务则随其提交（极端情况下看护后续异常回滚会丢掉这条告警，但下一轮看护
     * 会重新命中；未读抑制保证不会因此刷屏）。
     */
    private PlatformNotification persist(AlertRequest request) {
        PlatformNotification n = new PlatformNotification();
        n.setUserId(request.userId());
        n.setLevel(normalizeLevel(request.level()));
        n.setTitle(clip(request.title(), 200));
        n.setBody(request.body());
        n.setSessionId(request.sessionId());
        n.setAgentId(request.agentId());
        n.setAlertKey(request.alertKey());
        return repository.save(n);
    }

    private void pushStream(PlatformNotification n) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // kind 字段是前端的分流依据：SSE 的 `event:` 名在前端解析链路上会丢失
        // （只按行解析 data），无法用事件名区分 run-activity 与 session-alert。
        payload.put("kind", "alert");
        payload.put("id", n.getId() == null ? null : n.getId().toString());
        payload.put("level", n.getLevel());
        payload.put("title", n.getTitle());
        payload.put("body", n.getBody());
        payload.put("sessionId", n.getSessionId());
        payload.put("agentId", n.getAgentId());
        payload.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
        try {
            registry.send(n.getUserId(), EVENT_SESSION_ALERT, payload);
        } catch (RuntimeException e) {
            log.debug("alert: sse push failed: {}", e.getMessage());
        }
    }

    /** 达到阈值才外发；异步执行，失败只记 WARN。 */
    private void maybePushWecom(PlatformNotification n) {
        AlertProperties.Wecom wecom = props.getWecom();
        if (!wecom.isEnabled() || isBlank(wecom.getWebhookUrl())) {
            return;
        }
        if (rank(n.getLevel()) < rank(wecom.getMinLevel())) {
            return;
        }
        String markdown = renderWecom(n);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "markdown");
        body.put("markdown", Map.of("content", markdown));
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(wecom.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(Math.max(wecom.getTimeoutSeconds(), 1)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .exceptionally(ex -> {
                        log.warn("alert: wecom webhook failed: {}", ex.getMessage());
                        return null;
                    })
                    .thenAccept(resp -> {
                        if (resp != null && resp.statusCode() >= 400) {
                            log.warn("alert: wecom webhook returned {} : {}",
                                    resp.statusCode(), clip(resp.body(), 200));
                        }
                    });
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("alert: wecom webhook dispatch failed: {}", e.getMessage());
        }
    }

    private String renderWecom(PlatformNotification n) {
        StringBuilder sb = new StringBuilder();
        sb.append("**[").append(n.getLevel().toUpperCase()).append("] ").append(n.getTitle()).append("**\n");
        if (!isBlank(n.getBody())) {
            sb.append("\n").append(n.getBody()).append("\n");
        }
        if (!isBlank(n.getSessionId())) {
            sb.append("\n> 会话：`").append(n.getSessionId()).append("`\n");
        }
        sb.append("> 时间：").append(n.getCreatedAt() == null ? Instant.now() : n.getCreatedAt());
        return clip(sb.toString(), WECOM_CONTENT_LIMIT);
    }

    private static int rank(String level) {
        if (LEVEL_ERROR.equalsIgnoreCase(level)) {
            return 3;
        }
        if (LEVEL_WARN.equalsIgnoreCase(level)) {
            return 2;
        }
        return 1;
    }

    private static String normalizeLevel(String level) {
        if (LEVEL_ERROR.equalsIgnoreCase(level) || LEVEL_WARN.equalsIgnoreCase(level)) {
            return level.toLowerCase();
        }
        return LEVEL_INFO;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isAnonymous(String userId) {
        return "anonymous".equals(userId);
    }

    /**
     * 告警内容。
     *
     * @param level     info / warn / error
     * @param title     一句标题
     * @param body      详情（可空）
     * @param userId    归属用户（必填）
     * @param sessionId 关联会话（可空）
     * @param agentId   关联智能体（可空）
     * @param alertKey  去重键（可空；周期性看护必须给，否则会刷屏）
     */
    public record AlertRequest(String level,
                               String title,
                               String body,
                               String userId,
                               String sessionId,
                               String agentId,
                               String alertKey) {

        public static AlertRequest of(String level, String title, String body,
                                      String userId, String sessionId, String alertKey) {
            return new AlertRequest(level, title, body, userId, sessionId, null, alertKey);
        }
    }
}
