package com.tipdm.agentplatform.service.notify;

import com.tipdm.agentplatform.domain.SessionAlertPush;
import com.tipdm.agentplatform.repository.SessionAlertPushRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 会话级告警推送订阅（对话页推送面板的后端语义）。
 *
 * <p><b>opt-in + 显式选渠道</b>（V44 起）：推送到哪些渠道是用户在面板里主动
 * 勾选的，不是「开关一开推全部」。</p>
 * <ul>
 *   <li>无记录 / enabled=false → 不扇出（会话告警只走站内铃铛/SSE）；</li>
 *   <li>enabled=true 且勾选了渠道 → 只扇出勾选的渠道（仍按各渠道 min_level
 *       过滤；勾选的渠道被删除/停用后自动失效）；</li>
 *   <li>enabled=true 但没勾任何渠道 → 不扇出；</li>
 *   <li>全局告警（无 sessionId）不受会话白名单约束，扇出侧自行全量处理。</li>
 * </ul>
 *
 * <p>越权防护：扇出侧（{@code AlertChannelDispatcher}）只加载「该归属人自己
 * 的启用渠道」，白名单里混入的他人渠道 id 天然无匹配，无需在本服务再查一遍。</p>
 */
@Service
public class SessionAlertPushService {

    private static final Logger log = LoggerFactory.getLogger(SessionAlertPushService.class);

    /** 白名单上限：渠道个位数是常态，留余量防误传超长列表。 */
    private static final int MAX_CHANNEL_IDS = 50;

    private final SessionAlertPushRepository repository;

    public SessionAlertPushService(SessionAlertPushRepository repository) {
        this.repository = repository;
    }

    /**
     * 解析会话告警的扇出渠道白名单。
     *
     * @return null = 无会话维度（全局告警），扇出侧按用户全部启用渠道处理；
     *         空 list = 不扇出；非空 = 只扇出这些渠道
     */
    public List<UUID> resolveFanoutChannelIds(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return null; // 全局告警不受会话开关约束
        }
        try {
            return repository.findByUserIdAndSessionId(userId, sessionId)
                    .filter(SessionAlertPush::isEnabled)
                    .map(SessionAlertPush::getChannelIds)
                    .map(SessionAlertPushService::parseChannelIds)
                    .orElse(List.of());
        } catch (RuntimeException e) {
            log.debug("alert-push: subscription check failed (user={}, session={}): {}",
                    userId, sessionId, e.getMessage());
            return List.of(); // 查不动按未订阅处理：宁可少发 IM，不可把站内告警也吞了
        }
    }

    /** 当前生效状态（用于面板开关展示）。 */
    public boolean effectiveEnabled(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            return repository.findByUserIdAndSessionId(userId, sessionId)
                    .map(SessionAlertPush::isEnabled)
                    .orElse(false);
        } catch (RuntimeException e) {
            log.debug("alert-push: effective check failed (user={}, session={}): {}",
                    userId, sessionId, e.getMessage());
            return false;
        }
    }

    /** 是否有显式记录（false = 从未在面板操作过）。 */
    public boolean isExplicit(String userId, String sessionId) {
        return repository.findByUserIdAndSessionId(userId, sessionId).isPresent();
    }

    /** 当前勾选的渠道 id（面板回显用；无记录 = 空列表）。 */
    public List<String> selectedChannelIds(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return repository.findByUserIdAndSessionId(userId, sessionId)
                .map(row -> List.of(row.getChannelIds().split(",")))
                .map(list -> list.stream().filter(s -> !s.isBlank()).toList())
                .orElse(List.of());
    }

    /**
     * 设置订阅状态与渠道白名单（upsert）。
     *
     * @param channelIds 渠道 id 列表；null = 保持原值不变（只切开关）；
     *                   空列表 = 清空白名单（enabled=true 时也不外发）
     */
    @Transactional
    public void set(String userId, String sessionId, boolean enabled, List<String> channelIds) {
        Optional<SessionAlertPush> existing = repository.findByUserIdAndSessionId(userId, sessionId);
        SessionAlertPush row = existing.orElseGet(() -> {
            SessionAlertPush created = new SessionAlertPush();
            created.setUserId(userId);
            created.setSessionId(sessionId);
            return created;
        });
        row.setEnabled(enabled);
        if (channelIds != null) {
            row.setChannelIds(normalizeChannelIds(channelIds));
        }
        repository.save(row);
        log.info("alert-push: subscription set (user={}, session={}, enabled={}, channels=[{}])",
                userId, sessionId, enabled, row.getChannelIds());
    }

    /** 去重保序、去空白、限数量；非法 UUID 直接丢弃（前端只回传合法 id）。 */
    static String normalizeChannelIds(List<String> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return "";
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String raw : channelIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                deduped.add(UUID.fromString(raw.trim()).toString());
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 一律丢弃，不因脏数据整体失败
            }
            if (deduped.size() >= MAX_CHANNEL_IDS) {
                break;
            }
        }
        return String.join(",", deduped);
    }

    private static List<UUID> parseChannelIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                result.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // 脏数据跳过
            }
        }
        return result;
    }
}
