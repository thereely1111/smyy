package com.tipdm.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HITL 审批超时配置。
 *
 * <p>闸门/表单（ui_gate / ui_form_advanced）挂起等待主编提交超过
 * {@code approvalTimeoutMinutes} 后，由 {@code HitlTimeoutReaper} 巡检自动取消：
 * 关闭中断（解锁 AG-UI 线程）、在会话上下文中补一条「超时取消」工具结果（对齐
 * {@code HitlRecoveryGuardMiddleware} 的 SUCCESS=已决口径，防止模型/前端把取消后
 * 的闸门再当未决渲染）、并推送用户通知。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.hitl-timeout")
public class HitlTimeoutProperties {

    /** 是否启用审批超时自动取消。 */
    private boolean enabled = true;

    /** 审批超时阈值（分钟）：挂起超过该时长即自动取消。 */
    private long approvalTimeoutMinutes = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getApprovalTimeoutMinutes() {
        return approvalTimeoutMinutes;
    }

    public void setApprovalTimeoutMinutes(long approvalTimeoutMinutes) {
        this.approvalTimeoutMinutes = approvalTimeoutMinutes;
    }
}
