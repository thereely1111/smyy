package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.SessionProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * L3 停滞看护调度：周期驱动 {@link SessionProgressService#scanAndGuard()}。
 *
 * <p>补醒判据自 2026-09-16（V40）起为<b>平台自有事实</b>：无活跃 run + inbox 无待消费条目
 * + 无未决 HITL 登记 + idle 超阈值 + 未达补醒上限。模型自报的 {@code [进度]} 行只用于
 * 展示与告警文案，<b>不再参与决策</b>——旧判据要求 next_action=CONTINUE，而 CONTINUE 依赖
 * 模型守约，模型不守约判为 UNKNOWN 时看护既不补醒也不告警，会话只能静默空转
 * （教材会话实证连续空转 7 小时）。</p>
 */
@Component
public class SessionProgressWatchdog {

    private static final Logger log = LoggerFactory.getLogger(SessionProgressWatchdog.class);

    private final SessionProgressService progressService;

    public SessionProgressWatchdog(SessionProgressService progressService) {
        this.progressService = progressService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() {
        try {
            progressService.scanAndGuard();
        } catch (Throwable t) {
            // 看护是兜底：任何异常都不能把调度线程打死
            log.error("Session progress watchdog sweep failed", t);
        }
    }
}
