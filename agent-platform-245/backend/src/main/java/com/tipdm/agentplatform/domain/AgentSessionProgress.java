package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 会话进度台账（L3 停滞看护的单一事实源）。
 *
 * <p>字段与意义：</p>
 * <ul>
 *   <li>{@code nextAction}：父智能体下一步语义分类——CONTINUE（应继续推进，停滞看护关注对象）、
 *       WAIT_HITL（等主编闸门/表单，看护必须跳过）、DONE（已完成）、IDLE / UNKNOWN。</li>
 *   <li>{@code progressLine}：父自报的 `[进度]` 原文行（AGENTS 约定），未自报时为空。</li>
 *   <li>{@code lastStallWakeAt}：最近一次「疑似停滞补醒」时间，用于看护防抖（如 30 分钟限一次）。</li>
 * </ul>
 */
@Entity
@Table(name = "agent_session_progress")
public class AgentSessionProgress extends BaseEntity {

    /** 智能体 id（父智能体；file-agent 为确定性 UUID，配置 agent 为库表 UUID）。 */
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    /** 会话 id（前端生成的 {@code main-{uuid}}，AG-UI threadId）。 */
    @Column(name = "session_id", nullable = false, length = 255)
    private String sessionId;

    /** 归属用户（冗余自 owners 表，便于按用户查询）。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 阶段/里程碑标签（从 [进度] 行解析，如「二」或自由文本）。 */
    @Column(name = "phase", length = 64)
    private String phase;

    /** 下一步语义分类，见类注释。 */
    @Column(name = "next_action", nullable = false, length = 32)
    private String nextAction;

    /** 父自报 [进度] 行原文（可空）。 */
    @Column(name = "progress_line", columnDefinition = "TEXT")
    private String progressLine;

    /** 转录尾文本摘要（最近一条助手/系统文本），供人工排查。 */
    @Column(name = "last_text", length = 2000)
    private String lastText;

    /** 会话最近一次活跃（取自转录尾消息时间戳；无则落库时间）。 */
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /** 最近一次疑似停滞补醒时间（看护防抖用）。 */
    @Column(name = "last_stall_wake_at")
    private Instant lastStallWakeAt;

    /**
     * 本轮停滞已补醒次数（V40）。
     *
     * <p>产生实质进展时归零。达 {@code progress-watchdog.max-wake-attempts} 后看护
     * 停止补醒并置 {@link #needsHuman} —— 判据改成平台事实后 UNKNOWN 也会补醒，
     * 没有封顶就会从「静默空转」退化成「无限补醒烧 token」。</p>
     */
    @Column(name = "wake_count", nullable = false)
    private int wakeCount;

    /**
     * 最近一次实质进展时间（V40）：新消息或尾文本变化即刷新。
     * 补醒本身不算进展，否则计数永远归零、封顶形同虚设。
     */
    @Column(name = "last_progress_at")
    private Instant lastProgressAt;

    /** 补醒达上限仍无进展 → 转人工介入，看护不再自愈（V40）。 */
    @Column(name = "needs_human", nullable = false)
    private boolean needsHuman;

    /**
     * 用户停止标记（V60）：interrupt 置位、用户主动发起新 run 时清除。
     *
     * <p>置位期间所有「自发 run」唤醒源全部静默：框架 wakeup（子智能体完成回执、
     * 收件箱补醒、任务补投递、启动恢复）在 {@code AguiWakeupTarget.runWakeup} 入口
     * 拦截，停滞看护的自检注入在 {@code shouldWake} 拦截。没有它，「停止」只杀当前
     * run——被中断 pipeline 的子任务回执会立刻事件驱动拉起下一轮，形成复活循环
     * （2026-09-29 实证：interrupt 后 2 分钟内被回执 hint 重新唤醒，清数据永远输给
     * 事件推送）。</p>
     */
    @Column(name = "stop_requested", nullable = false)
    private boolean stopRequested;

    /**
     * 最近一次「纯文本收尾」登记时间（V61）：宣告式收尾检测器观察到 run 以
     * 「末条助手文本后无工具调用」正常收场时写入。
     *
     * <p>写入语义同 {@link #stopRequested}：进度周期 upsert 不覆盖（冲突保留旧值），
     * 仅由检测器登记入口显式更新。停滞看护读到「该时间已过宽限期、其后无新消息、
     * 完成契约未核验通过」时提前补醒，不必等满停滞阈值。</p>
     */
    @Column(name = "last_text_ending_at")
    private Instant lastTextEndingAt;

    /**
     * 等人工唤醒封存标记（V62）：HITL 审批超时被平台自动取消时置位，
     * 只有用户主动发起新 run（AG-UI 请求入口）才清除。
     *
     * <p>与 {@link #stopRequested} 是同类闸门但语义不同，两列独立并存：前者是
     * 用户主动喊停（interrupt 置位），本列是平台超时取消后的被动冻结。任一置位
     * 即禁止一切自发 run。</p>
     *
     * <p>存在的理由（2026-10-05 main-3f077bd9 实证）：超时取消摘掉 pending 登记
     * 后会话即「解冻」，此前被闸门挡住的唤醒通道恢复驱动能力，叠加 inbox 里积压的
     * 子任务完成通知，超时取消本身成了「继续跑」的触发器——01:30:07 取消、
     * 01:35:35 就起了新 run 输出 3673 条事件。超时 ≠ 批准，平台不得代替主编推进。
     * 拦截点与 {@link #stopRequested} 对称，见 {@code AguiWakeupTarget.runWakeup}
     * 入口与 {@code SessionProgressService.shouldWake}。</p>
     *
     * <p>写入语义同 {@link #stopRequested}：进度周期 upsert 不覆盖（冲突保留旧值），
     * 仅由超时取消置位、用户新 run 清除两个显式入口修改。</p>
     */
    @Column(name = "awaiting_human_gate", nullable = false)
    private boolean awaitingHumanGate;

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getNextAction() {
        return nextAction;
    }

    public void setNextAction(String nextAction) {
        this.nextAction = nextAction;
    }

    public String getProgressLine() {
        return progressLine;
    }

    public void setProgressLine(String progressLine) {
        this.progressLine = progressLine;
    }

    public String getLastText() {
        return lastText;
    }

    public void setLastText(String lastText) {
        this.lastText = lastText;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Instant getLastStallWakeAt() {
        return lastStallWakeAt;
    }

    public void setLastStallWakeAt(Instant lastStallWakeAt) {
        this.lastStallWakeAt = lastStallWakeAt;
    }

    public int getWakeCount() {
        return wakeCount;
    }

    public void setWakeCount(int wakeCount) {
        this.wakeCount = wakeCount;
    }

    public Instant getLastProgressAt() {
        return lastProgressAt;
    }

    public void setLastProgressAt(Instant lastProgressAt) {
        this.lastProgressAt = lastProgressAt;
    }

    public boolean isNeedsHuman() {
        return needsHuman;
    }

    public void setNeedsHuman(boolean needsHuman) {
        this.needsHuman = needsHuman;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public void setStopRequested(boolean stopRequested) {
        this.stopRequested = stopRequested;
    }

    public Instant getLastTextEndingAt() {
        return lastTextEndingAt;
    }

    public void setLastTextEndingAt(Instant lastTextEndingAt) {
        this.lastTextEndingAt = lastTextEndingAt;
    }

    public boolean isAwaitingHumanGate() {
        return awaitingHumanGate;
    }

    public void setAwaitingHumanGate(boolean awaitingHumanGate) {
        this.awaitingHumanGate = awaitingHumanGate;
    }
}
