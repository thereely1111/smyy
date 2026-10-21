package com.tipdm.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * L3 停滞看护配置：阈值从配置文件读取，便于按部署环境调整。
 *
 * <p><b>字段默认值必须与 {@code application.yml} 里的
 * {@code agent-platform.progress-watchdog.*} 保持一致</b>。本类的初始值只在
 * 「配置键缺失」时兜底，而键缺失通常是拼错键名或裁剪部署包所致——此时静默用
 * 一组语义不同的数字，比启动失败更难查。例：曾出现代码默认
 * {@code wakeCooldownMinutes=30} 而 yml 是 {@code 15}，读到代码的人会以为补醒
 * 间隔是 30 分钟，实际是 15 分钟。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.progress-watchdog")
public class ProgressWatchdogProperties {

    /** 停滞判定阈值（分钟）：超过该分钟无活动且该继续推进 → 疑似停滞。 */
    private long stallMinMinutes = 8;

    /** 同一会话两次补醒最小间隔（分钟），避免反复唤醒烧 token。 */
    private long wakeCooldownMinutes = 15;

    /** 扫描只看最近活跃过的会话（小时）。 */
    private long activeWindowHours = 24;

    /**
     * 停滞告知阈值（分钟）：会话空闲超过该值且未被判定为「已完成」，即向用户推一条
     * 告警。与 {@code stallMinMinutes} 分离——后者只决定要不要自动补醒，
     * 前者决定要不要告诉人；无法判定补醒的会话（UNKNOWN）也必须能被看见。
     */
    private long stallAlertMinMinutes = 30;

    /**
     * HITL 等待告知阈值（分钟）：会话存在挂起的表单/确认（ASKING 或活闸门登记）
     * 且空闲超过该值，即推送一条独立的「等待你输入」通知。与停滞告知分离——
     * 等人工不是「停摆」，用户打开会话处理即可，因此用独立去重键且阈值显著更低。
     */
    private long hitlAlertMinMinutes = 3;

    public long getStallMinMinutes() {
        return stallMinMinutes;
    }

    public void setStallMinMinutes(long stallMinMinutes) {
        this.stallMinMinutes = stallMinMinutes;
    }

    public long getWakeCooldownMinutes() {
        return wakeCooldownMinutes;
    }

    public void setWakeCooldownMinutes(long wakeCooldownMinutes) {
        this.wakeCooldownMinutes = wakeCooldownMinutes;
    }

    public long getActiveWindowHours() {
        return activeWindowHours;
    }

    public void setActiveWindowHours(long activeWindowHours) {
        this.activeWindowHours = activeWindowHours;
    }

    public long getStallAlertMinMinutes() {
        return stallAlertMinMinutes;
    }

    public void setStallAlertMinMinutes(long stallAlertMinMinutes) {
        this.stallAlertMinMinutes = stallAlertMinMinutes;
    }

    public long getHitlAlertMinMinutes() {
        return hitlAlertMinMinutes;
    }

    public void setHitlAlertMinMinutes(long hitlAlertMinMinutes) {
        this.hitlAlertMinMinutes = hitlAlertMinMinutes;
    }

    /**
     * 同一轮停滞最多补醒几次（V40）。
     *
     * <p>判据改为平台事实后，UNKNOWN/IDLE 也会补醒——没有封顶，一个卡死的会话会
     * 每 {@code wakeCooldownMinutes} 分钟补醒一次，无限烧 token 且永不收敛。
     * 达上限即停止自愈、置 needs_human 并升级为 error 级告警转人工。</p>
     */
    private int maxWakeAttempts = 3;

    public int getMaxWakeAttempts() {
        return maxWakeAttempts;
    }

    public void setMaxWakeAttempts(int maxWakeAttempts) {
        this.maxWakeAttempts = maxWakeAttempts;
    }

    /**
     * 租约心跳新鲜度门槛（分钟）：停滞闸门只把「心跳在该窗口内」的租约视为
     * 「有 run 在跑」（3.2 假活锁修复）。心跳停止超过该窗口的租约是僵尸凭证
     * ——闸门不再被它关死，自检接管；僵尸租约本体由 RunLeaseReconciler 清理。
     * 心跳由巡检每分钟续写，5 分钟 ≫ 正常心跳间隔，不会误伤真实在跑 run。
     */
    private long leaseStaleMinutes = 5;

    public long getLeaseStaleMinutes() {
        return leaseStaleMinutes;
    }

    public void setLeaseStaleMinutes(long leaseStaleMinutes) {
        this.leaseStaleMinutes = leaseStaleMinutes;
    }

    /**
     * 纯文本收尾补醒宽限期（分钟，V61）：run 以「宣告后续动作的纯文本」收场被
     * 登记后，超过该宽限期且其后无任何新消息、完成契约未核验通过 ⇒ 提前补醒，
     * 不等满 {@code stallMinMinutes}。宽限期的作用：模型轮末向用户提问（等回复）
     * 与「宣告不执行」结构上无法区分，给用户的正常回复留出时间窗；回复到达后
     * last_active_at 晚于登记时间，快速通道自动失效。
     */
    private long textEndingGraceMinutes = 3;

    public long getTextEndingGraceMinutes() {
        return textEndingGraceMinutes;
    }

    public void setTextEndingGraceMinutes(long textEndingGraceMinutes) {
        this.textEndingGraceMinutes = textEndingGraceMinutes;
    }
}
