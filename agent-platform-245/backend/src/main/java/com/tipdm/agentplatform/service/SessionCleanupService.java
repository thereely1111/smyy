package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.repository.AgentSessionProgressRepository;
import com.tipdm.agentplatform.repository.AguiEventRecordRepository;
import com.tipdm.agentplatform.repository.SessionAlertPushRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话清理兜底链：删除会话时把散落在各会话域表里的行一次清干净。
 *
 * <p>背景（2026-09-19 实测）：clearSession 原先只清状态行、归属行、产物目录与
 * inbox 队列，agui_events（长会话可达数千行）、agent_session_progress、
 * session_alert_push、agent_session_extensions 全部残留——表现即「会话删除不彻底」，
 * 且 inbox 残留条目让 InboxWatchdog 每 30 秒 WARN 一次。</p>
 *
 * <p><b>每项独立 try/catch</b>：清理是尽力而为的兜底，一项失败（如某表结构异常）
 * 不应中断其余项——否则又制造新的「删除不彻底」。各表删除均按 sessionId 匹配，
 * 不依赖 userId/agentId（半删状态下归属行可能已不在，无法反查归属）。</p>
 */
@Service
public class SessionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupService.class);

    private final AguiEventRecordRepository aguiEventRepository;
    private final AgentSessionProgressRepository progressRepository;
    private final SessionAlertPushRepository alertPushRepository;
    private final SessionExtensionService sessionExtensionService;

    public SessionCleanupService(AguiEventRecordRepository aguiEventRepository,
                                 AgentSessionProgressRepository progressRepository,
                                 SessionAlertPushRepository alertPushRepository,
                                 SessionExtensionService sessionExtensionService) {
        this.aguiEventRepository = aguiEventRepository;
        this.progressRepository = progressRepository;
        this.alertPushRepository = alertPushRepository;
        this.sessionExtensionService = sessionExtensionService;
    }

    /**
     * 清理某会话在所有会话域表中的残留行（扩展条目随扩展主行 ON DELETE CASCADE 级联删除）。
     *
     * @return 失败项数量（0 = 全部成功）
     */
    @Transactional
    public int purgeSessionData(String agentId, String sessionId) {
        int failures = 0;
        failures += step("agui_events", () -> {
            int n = aguiEventRepository.deleteBySessionId(sessionId);
            if (n > 0) {
                log.info("Session cleanup: deleted {} agui event rows, session={}", n, sessionId);
            }
        });
        failures += step("agent_session_progress", () ->
                progressRepository.deleteBySessionId(sessionId));
        failures += step("session_alert_push", () ->
                alertPushRepository.deleteBySessionId(sessionId));
        failures += step("agent_session_extensions", () -> {
            if (agentId != null && !agentId.isBlank()) {
                sessionExtensionService.deleteAll(agentId, sessionId);
            } else {
                // 半删状态下 agentId 可能查不到：按 session 全量兜底
                sessionExtensionService.deleteBySessionId(sessionId);
            }
        });
        return failures;
    }

    private int step(String table, Runnable action) {
        try {
            action.run();
            return 0;
        } catch (Exception e) {
            log.warn("Session cleanup: failed to purge {}: {}", table, e.getMessage());
            return 1;
        }
    }
}
