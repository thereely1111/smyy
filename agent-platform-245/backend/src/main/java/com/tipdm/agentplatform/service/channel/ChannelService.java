package com.tipdm.agentplatform.service.channel;

import com.tipdm.agentplatform.domain.AlertChannel;
import com.tipdm.agentplatform.domain.ChannelCredential;
import com.tipdm.agentplatform.repository.AlertChannelRepository;
import com.tipdm.agentplatform.repository.ChannelCredentialRepository;
import com.tipdm.agentplatform.service.ExternalApiKeyCipher;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 渠道管理业务：用户级出站渠道的 CRUD / 启停 / 连通性测试。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>归属即权限：所有读写强制带 userId，越权 id 一律按 404 处理；</li>
 *   <li>凭据存在 {@link ChannelCredential} 关联表，只在 create 时必填、
 *       update 时可选<b>整体覆盖</b>（先删后写），出参永不回显；</li>
 *   <li>API 层的 {@code platform_config} 是兼容形状，实际只承载
 *       {@code min_level} 一个键，落 channels.min_level 真实列。</li>
 * </ul>
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final AlertChannelRepository repository;
    private final ChannelCredentialRepository credentialRepository;
    private final ChannelTypeRegistry registry;
    private final AlertChannelDispatcher dispatcher;
    private final ExternalApiKeyCipher cipher;

    public ChannelService(AlertChannelRepository repository,
                          ChannelCredentialRepository credentialRepository,
                          ChannelTypeRegistry registry,
                          AlertChannelDispatcher dispatcher,
                          ExternalApiKeyCipher cipher) {
        this.repository = repository;
        this.credentialRepository = credentialRepository;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.cipher = cipher;
    }

    public List<Map<String, Object>> listTypes() {
        return registry.listTypes();
    }

    public List<AlertChannel> list(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public AlertChannel create(String userId, Map<String, Object> body) {
        String channelType = str(body.get("channel_type"));
        if (!registry.isSupported(channelType)) {
            throw ApiException.badRequest("不支持的渠道类型: " + channelType);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> credentials = mapOrEmpty((Map<String, Object>) body.get("credentials"));
        List<String> missing = registry.missingCredentials(channelType, credentials);
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("缺少必填凭据: " + String.join(", ", missing));
        }
        AlertChannel channel = new AlertChannel();
        channel.setUserId(userId);
        channel.setChannelType(channelType);
        channel.setName(clipName(body.get("name")));
        channel.setMinLevel(extractMinLevel(body));
        Object enabledRaw = body.get("enabled");
        channel.setEnabled(enabledRaw == null || Boolean.parseBoolean(enabledRaw.toString()));
        channel = repository.save(channel);
        replaceCredentials(channel.getId(), credentials);
        return channel;
    }

    @Transactional
    public AlertChannel update(String userId, String id, Map<String, Object> body) {
        AlertChannel channel = requireOwned(userId, id);
        if (body.containsKey("name")) {
            channel.setName(clipName(body.get("name")));
        }
        if (body.containsKey("credentials")) {
            // 整体覆盖语义：不与旧凭据合并，避免「改了个键旧密钥仍残留」
            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = mapOrEmpty((Map<String, Object>) body.get("credentials"));
            List<String> missing = registry.missingCredentials(channel.getChannelType(), credentials);
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("缺少必填凭据: " + String.join(", ", missing));
            }
            replaceCredentials(channel.getId(), credentials);
            // 凭据换了，旧的连通结论作废
            channel.setStatus("stopped");
            channel.setLastError(null);
        }
        if (body.containsKey("platform_config")) {
            channel.setMinLevel(extractMinLevel(body));
        }
        if (body.containsKey("enabled")) {
            Object enabledRaw = body.get("enabled");
            channel.setEnabled(enabledRaw != null && Boolean.parseBoolean(enabledRaw.toString()));
        }
        return repository.save(channel);
    }

    @Transactional
    public void delete(String userId, String id) {
        AlertChannel channel = requireOwned(userId, id);
        // 凭据行由 ON DELETE CASCADE 随渠道一起删（bulk delete 绕过持久层级联）
        credentialRepository.deleteByChannelId(channel.getId());
        repository.delete(channel);
    }

    @Transactional
    public AlertChannel setEnabled(String userId, String id, boolean enabled) {
        AlertChannel channel = requireOwned(userId, id);
        channel.setEnabled(enabled);
        if (!enabled) {
            channel.setStatus("stopped");
        }
        return repository.save(channel);
    }

    /** 发送测试消息并落结果。 */
    public Map<String, Object> sendTest(String userId, String id) {
        AlertChannel channel = requireOwned(userId, id);
        return dispatcher.sendTest(channel);
    }

    public AlertChannel requireOwned(String userId, String id) {
        UUID parsed;
        try {
            parsed = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("渠道 id 格式不合法");
        }
        return repository.findByIdAndUserId(parsed, userId)
                .orElseThrow(() -> ApiException.notFound("渠道不存在"));
    }

    /* ─────────────────────────── 出参组装 ─────────────────────────── */

    /**
     * 组装成前端 {@code ChannelRecord} 形状。credentials 不回显；
     * platform_config 回 {@code {min_level}}（真实列反填，兼容形状）；
     * routing / session 为入站能力预留位，出空对象。
     */
    public Map<String, Object> toRecord(AlertChannel channel) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", channel.getId() == null ? null : channel.getId().toString());
        row.put("channel_type", channel.getChannelType());
        row.put("name", channel.getName());
        row.put("user_id", channel.getUserId());
        row.put("platform_bot_id", null);
        row.put("enabled", channel.isEnabled());
        row.put("platform_config", Map.of("min_level", channel.getMinLevel() == null
                ? "warn" : channel.getMinLevel()));
        row.put("routing", Map.of());
        row.put("session", Map.of());
        row.put("created_at", channel.getCreatedAt() == null ? null : channel.getCreatedAt().toString());
        row.put("updated_at", channel.getUpdatedAt() == null ? null : channel.getUpdatedAt().toString());
        return row;
    }

    /** 出站型渠道没有常驻连接：状态由最近一次真实投递结果推导。 */
    public Map<String, Object> toStatus(AlertChannel channel) {
        String state;
        if (!channel.isEnabled()) {
            state = "stopped";
        } else if ("failed".equals(channel.getStatus())) {
            state = "failed";
        } else if (channel.getLastSentAt() != null) {
            state = "connected";
        } else {
            state = "stopped";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("last_error", channel.getLastError() == null ? "" : channel.getLastError());
        return body;
    }

    /* ─────────────────────────── 凭据写入 ─────────────────────────── */

    /** 整体覆盖：先删后写。调用方须在事务内。 */
    private void replaceCredentials(UUID channelId, Map<String, Object> credentials) {
        credentialRepository.deleteByChannelId(channelId);
        List<ChannelCredential> rows = new ArrayList<>();
        for (Map.Entry<String, Object> e : credentials.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            ChannelCredential row = new ChannelCredential();
            row.setChannelId(channelId);
            row.setCredKey(clip(e.getKey(), 100));
            // 凭据（加签 secret / Bearer token）加密落库：DB 拖库不应直接拿到用户 IM 凭据。
            // 读取侧兼容存量明文（见 AlertChannelDispatcher#decodeCredential）。
            row.setCredValue(cipher.encrypt(e.getValue() == null ? "" : e.getValue().toString()));
            rows.add(row);
        }
        credentialRepository.saveAll(rows);
    }

    /* ─────────────────────────── 工具 ─────────────────────────── */

    /** API 兼容形状里的 min_level（platform_config.min_level），非法值归一为 warn。 */
    private String extractMinLevel(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = mapOrEmpty((Map<String, Object>) body.get("platform_config"));
        return registry.normalizeMinLevel(
                config.get("min_level") == null ? null : config.get("min_level").toString());
    }

    private Map<String, Object> mapOrEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String clipName(Object value) {
        String name = value == null ? null : value.toString();
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.length() <= 200 ? name : name.substring(0, 200);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
