package com.tipdm.agentplatform.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.util.JsonUtils;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * 服务端自发 run 的 HITL 闸门登记引擎（缺陷修复 2026-09-13；2026-09-20 收口）。
 *
 * <p><b>收口说明（2026-09-20）</b>：登记逻辑原本只为 wakeup 路径服务；停滞自检
 * 入口（SessionProgressService）直接 {@code streamEvents().subscribe()} 漏掉了
 * 收尾登记，导致该路径渲染的闸门对超时巡检完全隐形（reaper 扫不到 → 30 分钟
 * 取消失效 → watchdog 反复注入自检 → 模型重渲染闸门死循环）。现由
 * {@link ServerSideRunLauncher} 对<b>所有</b>服务端自发 run 统一调用
 * {@link #registerSuspendedGates}——本类是登记引擎，不再是 wakeup 专属；
 * 新增服务端自发 run 入口时禁止绕过 launcher 自行 streamEvents。</p>
 *
 * <p><b>问题</b>：父会话被 wakeup 唤醒续跑时，模型调用 {@code ui_gate} /
 * {@code ui_form_advanced} 渲染闸门并以 {@code TOOL_SUSPENDED} 正常收尾。
 * 该 run 绕过了 AG-UI 的 {@code AguiRequestProcessor}（wakeup 直接
 * {@code streamEvents}），导致三处失联：</p>
 *
 * <ol>
 *   <li>框架 {@code AguiResumeCoordinator.pendingInterruptsByThread} 没有该中断
 *       （只有 AG-UI 请求路径的 {@code trackPendingInterrupts} 会登记）——前端后续
 *       resume 会被契约拒绝（"does not match any open interrupt"）；</li>
 *   <li>{@code PendingFormInterruptStore} 登记表为空（登记原依赖前端收到 SSE
 *       FORM_INTERRUPT 后上报，wakeup run 无订阅者）——前端刷新/轮询查不到表单；</li>
 *   <li>前端转录里 {@code tool_call:ui_gate} state={@code allowed} 且无
 *       tool_result，{@code restorePendingForm} 的状态过滤不认识——表单不渲染。
 *       闸门无人能提交，流程停滞（2026-09-13 实测：闸门1 在 wakeup 轮渲染后
 *       停滞 13+ 分钟，form-interrupts API 返回空数组）。</li>
 * </ol>
 *
 * <p><b>修复</b>：wakeup run 收尾时检查最终结果消息；若为 {@code TOOL_SUSPENDED}
 * 且含 HITL 工具的 suspended 结果，则由后端代替「不存在的订阅者」完成两处登记：
 * 反射写入框架 {@code pendingInterruptsByThread}（使标准 resume 契约成立）+
 * 写入平台 form-interrupts 登记表（使前端能恢复 interruptId 并渲染表单）。
 * 中断 id 与框架 AG-UI 路径的生成规则完全一致：{@code {replyId}:{toolCallId}}，
 * replyId 取最终结果消息 id（缺失时回退 toolCallId，与框架
 * {@code interruptId(Msg, String)} 同规则）。</p>
 *
 * <p>反射链 {@code AguiMvcController → processor → resumeCoordinator →
 * pendingInterruptsByThread} 与 {@link ResumeCoordinatorDeadlockCleaner} 同源，
 * 属已知框架耦合；字段改名时静默跳过并降级为「仅平台登记表」，前端 resume 失败
 * 后仍可走 user-message 降级路径（语义自洽，见 PendingFormInterruptStore 注释）。</p>
 */
@Component
public class WakeupInterruptRegistrar implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(WakeupInterruptRegistrar.class);

    private static final String TOOL_CALL_INTERRUPT_REASON = AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON;

    private final PendingFormInterruptStore pendingFormInterruptStore;
    private final ObjectMapper objectMapper;
    private final SessionNotifyPublisher notifyPublisher;
    private ApplicationContext applicationContext;

    /** 反射解析到的框架 pendingInterruptsByThread；null 表示尚未解析或解析失败。 */
    private volatile ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> pendingInterruptsCache;

    public WakeupInterruptRegistrar(
            PendingFormInterruptStore pendingFormInterruptStore,
            ObjectMapper objectMapper,
            SessionNotifyPublisher notifyPublisher) {
        this.pendingFormInterruptStore = pendingFormInterruptStore;
        this.objectMapper = objectMapper;
        this.notifyPublisher = notifyPublisher;
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        this.applicationContext = ctx;
    }

    /**
     * 检查 wakeup run 的最终结果消息，对其中挂起的 HITL 工具完成两处登记。
     * 幂等：非 TOOL_SUSPENDED / 无 suspended HITL 结果时不做任何事。
     */
    public void registerSuspendedGates(Msg result, String agentId, String userId, String sessionId) {
        if (result == null || sessionId == null || sessionId.isBlank()
                || result.getGenerateReason() != GenerateReason.TOOL_SUSPENDED) {
            return;
        }
        // 收集本消息内全部 tool_use（id → block），供 suspended 结果回查工具名/入参。
        Map<String, ToolUseBlock> toolUses = new LinkedHashMap<>();
        for (ContentBlock block : result.getContent()) {
            if (block instanceof ToolUseBlock toolUse
                    && toolUse.getId() != null && !toolUse.getId().isBlank()) {
                toolUses.put(toolUse.getId(), toolUse);
            }
        }
        String replyId = result.getId();
        for (ContentBlock block : result.getContent()) {
            if (!(block instanceof ToolResultBlock toolResult) || !toolResult.isSuspended()) {
                continue;
            }
            String toolCallId = toolResult.getId();
            ToolUseBlock toolUse = toolCallId == null ? null : toolUses.get(toolCallId);
            String toolName = toolUse != null ? toolUse.getName() : toolResult.getName();
            if (toolName == null || !HitlRecoveryGuardMiddleware.HITL_TOOLS.contains(toolName)) {
                continue;
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                log.warn("Wakeup gate: suspended HITL result without id, session={}, skipping", sessionId);
                continue;
            }
            String interruptId = (replyId == null || replyId.isBlank())
                    ? toolCallId
                    : replyId + ":" + toolCallId;
            registerAguiPendingInterrupt(sessionId, interruptId,
                    buildInterrupt(replyId, toolUse, toolResult, toolCallId));
            registerPlatformFormInterrupt(agentId, userId, sessionId, interruptId, replyId,
                    toolCallId, toolName, toolUse);
            // 实时通知发布点（interrupt）：闸门在后台 run 中挂起、前端 SSE 看不到，
            // 若不推信号，表单要等 30s 兜底轮询才浮现。尽力而为，失败不影响登记。
            notifyPublisher.publish(userId, sessionId, replyId == null ? "" : replyId, null,
                    SessionNotifyPublisher.REASON_INTERRUPT);
            log.warn(
                    "Wakeup gate: registered suspended HITL interrupt for session={} (tool={}, "
                            + "toolCallId={}, interruptId={}) — the wakeup run bypassed the AG-UI "
                            + "request path, so the platform registers it on behalf of the absent "
+ "SSE subscriber",
                    sessionId, toolName, toolCallId, interruptId);
        }
    }

    /**
     * 悬空闸门兜底对账：run 收尾后扫持久化上下文，若存在**没有登记在任何内存源里**的
     * 未决 HITL 闸门，补齐框架 + 平台两处登记。返回本次实际补登记的条数。
     *
     * <p><b>为什么 {@link #registerSuspendedGates} 单独不够</b>（2026-10-07实证）：
     * 它的入口判据是「最终结果消息的 {@code GenerateReason == TOOL_SUSPENDED}」，
     * 而闸门有三种收尾形态走不到这个判据：
     * <ol>
     *   <li>工具被框架转后台执行（tool 返回「running in background over 60s」提醒，
     *       run 正常收尾）⇒ 闸门 tool_use 写进了上下文却没有 suspended 结果；</li>
     *   <li>run 被中断 / max-iters 截断，收尾消息不是 TOOL_SUSPENDED；</li>
     *   <li>框架自动补错（{@code enablePendingToolRecovery}）写了 ERROR 结果，
     *       闸门实际已被消费。</li>
     * </ol>
     * 这些形态下两处登记全部跳过 ⇒ 框架 {@code pendingInterruptsByThread} 与平台
     * {@code pendingFormStore} 都没有该闸门 ⇒ 三方矛盾：
     * <ul>
     *   <li>前端刷新/轮询查不到表单，无人可提交；</li>
     *   <li>{@link HitlTimeoutReaper} 的 30 分钟硬超时扫不到它（只扫两处内存源），
     *       超时取消失效；</li>
     *   <li>{@code AguiWakeupTarget} 的闸门守卫却按
     *       {@code hasPendingHitl}（读上下文，与内存源无关）判「有未决闸门」，
     *       把所有唤醒永久延后。</li>
     * </ul>
     * 实证 main-3f077bd9：00:01→09:41 被挡 290 次，每次
     * {@code has an open HITL gate (0 live form interrupt(s), staleRecoverySpent=true)}。
     * 本方法把上下文这一路补上，让三方重新一致：表单可渲染、可提交、超时可取消、
     * 守卫认得出活闸门。
     *
     * <p><b>幂等</b>：登记表已有该 toolCallId 时不做任何事；重复调用无副作用。
     * <b>依赖同一判据</b>：未决判定走
     * {@link HitlRecoveryGuardMiddleware#findPendingHitlGate}，与唤醒守卫同源，
     * 杜绝「守卫认定未决、对账认定无此闸门」的口径分叉。
     *
     * @param store 会话状态存储（由 {@code HitlRecoveryGuardMiddleware#resolveStateStore}
     *              从 agent 实例解析——注意中间件链拿到的是内层 {@code ReActAgent}，
     *              不能要求 {@code instanceof HarnessAgent}，否则恒为 null）
     */
    public int reconcileDanglingGate(AgentStateStore store, String agentId, String userId,
                                     String sessionId) {
        if (store == null || agentId == null || userId == null
                || sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Optional<HitlRecoveryGuardMiddleware.PendingHitlGate> located =
                HitlRecoveryGuardMiddleware.findPendingHitlGate(store, userId, sessionId);
        if (located.isEmpty()) {
            return 0;
        }
        HitlRecoveryGuardMiddleware.PendingHitlGate gate = located.get();
        String toolCallId = gate.toolCallId();
        // 已有登记（含平台提交后残留但框架仍持有的情形）⇒ 不重复登记。
        boolean alreadyRegistered = pendingFormInterruptStore.listBySession(agentId, sessionId)
                .stream()
                .anyMatch(e -> toolCallId.equals(e.toolCallId()));
        if (alreadyRegistered) {
            return 0;
        }
        ToolUseBlock toolUse = gate.toolUse();
        String toolName = toolUse != null ? toolUse.getName() : null;
        if (toolName == null || !HitlRecoveryGuardMiddleware.HITL_TOOLS.contains(toolName)) {
            return 0;
        }
        String replyId = gate.replyId();
        String interruptId = (replyId == null || replyId.isBlank())
                ? toolCallId
                : replyId + ":" + toolCallId;
        registerAguiPendingInterrupt(sessionId, interruptId,
                buildInterrupt(replyId, toolUse, null, toolCallId));
        registerPlatformFormInterrupt(agentId, userId, sessionId, interruptId, replyId,
                toolCallId, toolName, toolUse);
        // 前端靠游标信号发现新闸门（否则要等 30s/120s 兜底轮询）；失败不影响登记。
        notifyPublisher.publish(userId, sessionId, replyId == null ? "" : replyId, null,
                SessionNotifyPublisher.REASON_INTERRUPT);
        log.warn(
                "Gate reconcile: registered dangling HITL gate for session={} (tool={}, toolCallId={}, "
                        + "interruptId={}) — the run did not finish as TOOL_SUSPENDED, so neither "
                        + "framework pendingInterruptsByThread nor the platform form store had it; "
                        + "without this the gate is unsubmittable, the 30min reaper cannot see it, "
                        + "and the wakeup guard defers every wakeup forever",
                sessionId, toolName, toolCallId, interruptId);
        return 1;
    }

    /** 构造与框架 AG-UI 路径同构的 Interrupt 事件（metadata 键名对齐 AgentLifecycleEventConverter）。 */
    private AguiEvent.Interrupt buildInterrupt(
            String replyId, ToolUseBlock toolUse, ToolResultBlock toolResult, String toolCallId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (toolUse != null && toolUse.getName() != null && !toolUse.getName().isBlank()) {
            metadata.put(AguiInterruptConstants.METADATA_TOOL_NAME, toolUse.getName());
        }
        if (toolUse != null && toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
            metadata.put(AguiInterruptConstants.METADATA_TOOL_INPUT, toolUse.getInput());
        }
        // toolUse 可能缺失（suspended 结果没有配对的 tool_use block）——上面两处都判了空，
        // 这里原先直接解引用，NPE 会从 AguiWakeupTarget 的 doOnNext 冒出，整次 wakeup
        // 判失败且后续闸门全部不再登记。补判空，退化为"无入参 JSON"。
        if (toolUse != null) {
            metadata.put(AguiInterruptConstants.METADATA_TOOL_CONTENT,
                    JsonUtils.resolveToolCallArgsJson(toolUse));
        }
        if (replyId != null && !replyId.isBlank()) {
            metadata.put(AguiInterruptConstants.METADATA_REPLY_ID, replyId);
        }
        return new AguiEvent.Interrupt(
                (replyId == null || replyId.isBlank()) ? toolCallId : replyId + ":" + toolCallId,
                TOOL_CALL_INTERRUPT_REASON,
                null,
                toolCallId,
                null,
                null,
                Map.copyOf(metadata));
    }

    /**
     * 反射登记进框架 {@code AguiResumeCoordinator.pendingInterruptsByThread}，
     * 使后续 AG-UI resume 的契约校验（必须覆盖全部 open interrupts）能通过。
     * 与已有条目合并；解析失败时仅告警（前端有 user-message 降级路径兜底）。
     */
    private void registerAguiPendingInterrupt(
            String threadId, String interruptId, AguiEvent.Interrupt interrupt) {
        ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> pending = resolvePendingInterrupts();
        if (pending == null) {
            log.warn("Wakeup gate: framework pendingInterruptsByThread not wired; "
                    + "resume for interrupt {} will degrade to user-message path", interruptId);
            return;
        }
        Map<String, AguiEvent.Interrupt> existing = pending.get(threadId);
        if (existing != null && existing.containsKey(interruptId)) {
            return;
        }
        // read-modify-write 必须在 compute 内完成：原实现 get → 新建 map → put，
        // 并发登记两个闸门时后写的一方会覆盖前一方（丢闸门），随后 resume 报
        // "must cover all open interrupts" 而整体被拒。
        pending.compute(threadId, (key, current) -> {
            Map<String, AguiEvent.Interrupt> merged = new LinkedHashMap<>();
            if (current != null) {
                merged.putAll(current);
            }
            merged.put(interruptId, interrupt);
            return Map.copyOf(merged);
        });
    }

    /** 平台 form-interrupts 登记表：前端刷新/轮询恢复表单与 interruptId 的数据源。 */
    private void registerPlatformFormInterrupt(
            String agentId, String userId, String sessionId, String interruptId, String replyId,
            String toolCallId, String toolName, ToolUseBlock toolUse) {
        String toolInputJson = "{}";
        if (toolUse != null && toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
            try {
                toolInputJson = objectMapper.writeValueAsString(toolUse.getInput());
            } catch (Exception e) {
                log.debug("Wakeup gate: failed to serialize tool input for {}: {}", toolCallId, e.getMessage());
            }
        }
        pendingFormInterruptStore.put(agentId, userId, sessionId, new PendingFormInterruptStore.PendingFormInterrupt(
                interruptId, replyId, toolCallId, toolName, toolInputJson, Instant.now().toString()));
    }

    /**
     * 把平台登记表条目回灌进框架 {@code pendingInterruptsByThread}（read-modify-write
     * 合并，幂等），使标准 AG-UI resume 契约校验（resume 必须覆盖全部 open interrupts）
     * 在框架映射丢失条目后仍能通过。返回本次实际补入的条数。
     *
     * <p><b>为什么需要回灌</b>：框架 {@code trackPendingInterrupts} 对该映射做的是
     * <b>整体 put 替换</b>（一次 run 收尾即可把其他来源登记的条目抹掉），而平台的
     * {@link PendingFormInterruptStore} 登记表不受此影响——因此登记表升为权威事实源，
     * 框架映射退化为「resume 契约校验所需的可失缓存」，消费方读取登记表时在此修复。
     * 不做回灌的后果：前端查到条目、发出 resume，框架却报
     * {@code Thread has unresolved interrupts}（映射被抹）而整体拒绝（2026-10-05
     * 闸门3 实测，登记成功 24s 后即漂移）。</p>
     *
     * <p>幂等性：条目已在框架映射中时不做任何事；反射不可达时告警并返回 0
     * （调用方仍返回登记表，resume 失败后走 user-message 降级路径，语义不变）。</p>
     *
     * <p><b>只给消费路径用</b>：超时巡检（{@link HitlTimeoutReaper}）的存活探针
     * {@link #filterLive} 不得改用本方法——巡检靠「框架映射没有 = 已消费/陈旧」清理
     * 死条目，先回灌会让死条目永生。代价：已消费条目的清理时效从「读取即剔除」放宽到
     * 「一个巡检周期内」（SWEEP 60s）。</p>
     */
    public int ensureFrameworkRegistration(
            String sessionId, List<PendingFormInterruptStore.PendingFormInterrupt> entries) {
        if (sessionId == null || sessionId.isBlank()
                || entries == null || entries.isEmpty()) {
            return 0;
        }
        ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> pending = resolvePendingInterrupts();
        if (pending == null) {
            log.warn("Wakeup gate: framework pendingInterruptsByThread not wired; "
                    + "resume for {} registered interrupt(s) of session {} may be rejected "
                    + "by the AG-UI contract and degrade to the user-message path",
                    entries.size(), sessionId);
            return 0;
        }
        int repaired = 0;
        for (PendingFormInterruptStore.PendingFormInterrupt entry : entries) {
            if (entry == null || entry.interruptId() == null || entry.interruptId().isBlank()) {
                continue;
            }
            String interruptId = entry.interruptId();
            boolean[] added = {false};
            pending.compute(sessionId, (key, current) -> {
                if (current != null && current.containsKey(interruptId)) {
                    return current;
                }
                AguiEvent.Interrupt event = interruptFromStore(entry);
                if (event == null) {
                    return current;
                }
                Map<String, AguiEvent.Interrupt> merged = new LinkedHashMap<>();
                if (current != null) {
                    merged.putAll(current);
                }
                merged.put(interruptId, event);
                added[0] = true;
                return Map.copyOf(merged);
            });
            if (added[0]) {
                repaired++;
            }
        }
        if (repaired > 0) {
            log.warn("Wakeup gate: repaired {} framework interrupt(s) from the platform "
                    + "registry for session={} — the framework map had lost them "
                    + "(trackPendingInterrupts put-overwrite); resume contract restored",
                    repaired, sessionId);
        }
        return repaired;
    }

    /** 从登记表条目重建与 {@link #buildInterrupt} 同构的框架中断事件（元数据键名对齐）。 */
    private AguiEvent.Interrupt interruptFromStore(
            PendingFormInterruptStore.PendingFormInterrupt entry) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (entry.toolName() != null && !entry.toolName().isBlank()) {
            metadata.put(AguiInterruptConstants.METADATA_TOOL_NAME, entry.toolName());
        }
        String toolInputJson = entry.toolInput();
        if (toolInputJson != null && !toolInputJson.isBlank()) {
            try {
                Object parsed = objectMapper.readValue(toolInputJson, Object.class);
                if (parsed instanceof Map<?, ?> inputMap && !inputMap.isEmpty()) {
                    metadata.put(AguiInterruptConstants.METADATA_TOOL_INPUT, inputMap);
                }
            } catch (Exception e) {
                log.debug("Wakeup gate: failed to parse stored tool input for {}: {}",
                        entry.toolCallId(), e.getMessage());
            }
            metadata.put(AguiInterruptConstants.METADATA_TOOL_CONTENT, toolInputJson);
        }
        if (entry.replyId() != null && !entry.replyId().isBlank()) {
            metadata.put(AguiInterruptConstants.METADATA_REPLY_ID, entry.replyId());
        }
        return new AguiEvent.Interrupt(
                entry.interruptId(),
                TOOL_CALL_INTERRUPT_REASON,
                null,
                entry.toolCallId(),
                null,
                null,
                Map.copyOf(metadata));
    }

    /**
     * 存活探针：按框架 {@code pendingInterruptsByThread} 过滤平台登记表条目，
     * 框架已不持有的视为已消费/陈旧并剔除。
     *
     * <p><b>动机（2026-09-13 实测）</b>：登记表只在 RUN_FINISHED(success) 时清空
     * （前端收尾时调 DELETE），闸门若经 cancelled resume / 重渲染被消费，其旧条目会
     * 残留。框架契约校验是<b>精确集合相等</b>——把陈旧 interruptId 塞进 resume[] 会报
     * unknown id 整体拒绝，连正确的闸门 resume 也一起失效，故探针必须剔除。</p>
     *
     * <p><b>规则</b>：框架映射对某线程非空时，只保留其中存在的 interruptId；映射为空
     * /缺失时视为「该线程已无活闸门」，全部丢弃；反射不可达时原样返回，降级安全。</p>
     *
     * <p><b>仅限探针用途</b>（超时巡检清理死条目、wakeup 陈旧闸门判定、通知流重播
     * 过滤）。消费路径（GET form-interrupts）不得用它剔除——框架映射会被自身
     * {@code trackPendingInterrupts} 的整体 put 抹掉条目，剔除会把活闸门对前端隐藏；
     * 消费路径应改用 {@link #ensureFrameworkRegistration} 回灌修复。</p>
     */
    public List<PendingFormInterruptStore.PendingFormInterrupt> filterLive(
            String sessionId, List<PendingFormInterruptStore.PendingFormInterrupt> entries) {
        if (entries == null || entries.isEmpty()) {
            return entries;
        }
        ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> pending = resolvePendingInterrupts();
        if (pending == null) {
            return entries;
        }
        Map<String, AguiEvent.Interrupt> live = pending.get(sessionId);
        if (live == null || live.isEmpty()) {
            log.warn("Wakeup gate: reconciled form-interrupts for session={} — framework has no "
                    + "open interrupt, treating {} entr(ies) as consumed/stale", sessionId, entries.size());
            return List.of();
        }
        List<PendingFormInterruptStore.PendingFormInterrupt> filtered = entries.stream()
                .filter(e -> e.interruptId() != null && live.containsKey(e.interruptId()))
                .toList();
        if (filtered.size() != entries.size()) {
            log.warn("Wakeup gate: reconciled form-interrupts for session={} — kept {}, dropped {} "
                    + "stale entr(ies) no longer in framework pendingInterruptsByThread",
                    sessionId, filtered.size(), entries.size() - filtered.size());
        }
        return filtered;
    }

    /**
     * 从框架 {@code pendingInterruptsByThread} 注销指定中断（HITL 审批超时取消用）。
     *
     * <p>与 {@link #registerAguiPendingInterrupt} 同一反射链，方向相反：移除后 AG-UI
     * resume 契约不再要求覆盖该中断，纯 messages run 即可解锁线程继续。compute 内完成
     * 移除；映射为空时返回 null 摘除整个线程条目。反射不可达时静默跳过（平台登记表
     * 仍会清理，框架残留的中断由 cancelled resume / user-message 降级路径兜底）。</p>
     */
    public void unregisterInterrupts(String sessionId, java.util.Collection<String> interruptIds) {
        if (sessionId == null || sessionId.isBlank()
                || interruptIds == null || interruptIds.isEmpty()) {
            return;
        }
        ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> pending = resolvePendingInterrupts();
        if (pending == null) {
            log.debug("Wakeup gate: framework pendingInterruptsByThread not wired; "
                    + "skipping unregister for session={}", sessionId);
            return;
        }
        pending.computeIfPresent(sessionId, (key, current) -> {
            Map<String, AguiEvent.Interrupt> merged = new LinkedHashMap<>(current);
            for (String id : interruptIds) {
                merged.remove(id);
            }
            return merged.isEmpty() ? null : Map.copyOf(merged);
        });
    }

    /**
     * 框架 {@code pendingInterruptsByThread} 只读快照（sessionId → interruptId → 中断事件）。
     *
     * <p>供超时巡检（{@link HitlTimeoutReaper}）对账：框架映射是「哪些中断仍活着」
     * 的权威事实源，平台登记表只是前端查询/巡检缓存——两者出现漂移（某入口漏登记）
     * 时以框架为准补齐。反射不可达返回空表（调用方降级跳过对账）。</p>
     */
    public Map<String, Map<String, AguiEvent.Interrupt>> snapshotFrameworkInterrupts() {
        ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> pending = resolvePendingInterrupts();
        return pending == null ? Map.of() : Map.copyOf(pending);
    }

    /**
     * 解析框架的 {@code pendingInterruptsByThread}（controller → processor →
     * coordinator → field）。失败返回 null 并当周期静默跳过。
     */
    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> resolvePendingInterrupts() {
        if (pendingInterruptsCache != null) {
            return pendingInterruptsCache;
        }
        if (applicationContext == null) {
            return null;
        }
        try {
            Object controller;
            try {
                Class<?> cls = Class.forName("io.agentscope.spring.boot.agui.mvc.AguiMvcController");
                controller = applicationContext.getBean(cls);
            } catch (ClassNotFoundException | RuntimeException primary) {
                try {
                    controller = applicationContext.getBean("aguiMvcController");
                } catch (RuntimeException fallback) {
                    log.debug("Wakeup gate: AguiMvcController bean not found");
                    return null;
                }
            }
            if (controller == null) {
                return null;
            }
            Object processor = readFieldByName(controller, "processor");
            if (processor == null) {
                return null;
            }
            Object coordinator = readFieldByName(processor, "resumeCoordinator");
            if (coordinator == null) {
                return null;
            }
            Object pending = readFieldByName(coordinator, "pendingInterruptsByThread");
            if (!(pending instanceof ConcurrentMap<?, ?> map)) {
                return null;
            }
            this.pendingInterruptsCache =
                    (ConcurrentMap<String, Map<String, AguiEvent.Interrupt>>) map;
            log.info("Wakeup gate: wired pendingInterruptsByThread via AguiMvcController"
                    + " → processor → resumeCoordinator");
            return pendingInterruptsCache;
        } catch (RuntimeException | IllegalAccessException e) {
            log.debug("Wakeup gate: pendingInterruptsByThread resolve failed: {}", e.toString());
            return null;
        }
    }

    /** 按字段名沿 class 链向上找 declared field（与 ResumeCoordinatorDeadlockCleaner 同实现）。 */
    private static Object readFieldByName(Object owner, String fieldName)
            throws IllegalAccessException {
        Class<?> c = owner.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
