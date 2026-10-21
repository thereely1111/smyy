package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.repository.AgentSessionProgressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 「纯文本收尾」登记器（V61）：宣告式收尾检测器观察到 run 以「末条助手文本后
 * 无工具调用」正常收场时调用。只登记事实、不做任何决策——是否补醒由停滞看护
 * 结合完成契约/等人工/停止抑制等完整事实判定（宽限期后提前唤醒）。
 *
 * <p>独立成 bean 是为切断循环依赖：DeclarativeEndingWatchdog 只需要写这一列，
 * 若注入 {@link SessionProgressService} 会形成
 * SessionProgressService → ServerSideRunLauncher → Watchdog → SessionProgressService
 * 的环。本类仅依赖仓储，看护读侧逻辑不受影响。</p>
 */
@Service
public class SessionTextEndingRecorder {

    private static final Logger log = LoggerFactory.getLogger(SessionTextEndingRecorder.class);

    private final AgentSessionProgressRepository progressRepository;

    public SessionTextEndingRecorder(AgentSessionProgressRepository progressRepository) {
        this.progressRepository = progressRepository;
    }

    @Transactional
    public void recordTextEnding(String agentId, String sessionId, String userId) {
        try {
            progressRepository.recordTextEnding(UUID.randomUUID(), agentId, sessionId,
                    userId == null ? "" : userId);
        } catch (RuntimeException e) {
            log.warn("Failed to record text ending for session {}: {}", sessionId, e.getMessage());
        }
    }
}
