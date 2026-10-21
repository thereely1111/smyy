package com.tipdm.agentplatform.config;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import com.tipdm.agentplatform.harness.SessionAgentRegistry;
import com.tipdm.agentplatform.harness.SessionPermissionReconciler;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.JwtService;
import com.tipdm.agentplatform.service.RunDebugSelection;
import com.tipdm.agentplatform.service.RunModelSelection;
import com.tipdm.agentplatform.service.RunWebSearchSelection;
import com.tipdm.agentplatform.service.SessionKnowledgeRef;
import com.tipdm.agentplatform.service.SessionKnowledgeService;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionSkillInvocation;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.service.SkillService;
import com.tipdm.agentplatform.service.credential.ModelCredentialService;
import com.tipdm.agentplatform.service.credential.PlatformModelDefaultService;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.harness.RunLeaseStore;
import com.tipdm.agentplatform.harness.SessionActivityTracker;
import com.tipdm.agentplatform.harness.WakeupInterruptRegistrar;
import com.tipdm.agentplatform.service.SessionProgressService;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AG-UI 运行时上下文解析器：把请求级登录用户（只认 JWT payload，无身份头兜底）
 * 写入 RuntimeContext，替换框架默认的共享命名空间，实现终端用户级会话隔离；
 * 并在 run 前置幂等创建该用户的会话产物目录（runtimes/{agentId}/{sessionId}），
 * 使 AG-UI 入口与平台自有 chat 入口的目录托管行为一致；同时记录会话归属到
 * agent_session_owner 表（两层目录不含 userId，归属靠表承载）。
 *
 * <p>不允许匿名使用：请求未携带有效登录身份时抛出 401（全局 {@code JwtAuthFilter}
 * 已对 /agui/** 强制登录，这里作为第二道防线）。
 *
 * <p>注册为 Bean 后由 agentscope-agui-spring-boot-starter 的
 * {@code ObjectProvider<AguiRuntimeContextResolver>.getIfAvailable()} 自动采用。</p>
 */
@Component
public class PlatformAguiRuntimeContextResolver implements AguiRuntimeContextResolver {

    private static final Logger log = LoggerFactory.getLogger(PlatformAguiRuntimeContextResolver.class);

    /** 单条消息最多注入的技能调用数（防异常请求塞爆系统提示）。 */
    private static final int MAX_SKILL_INVOCATIONS = 10;

    private final JwtService jwtService;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionOwnerService sessionOwnerService;
    private final SessionAgentRegistry sessionAgentRegistry;
    private final AgentAssemblyService assemblyService;
    private final SessionPermissionReconciler permissionReconciler;
    private final SessionKnowledgeService sessionKnowledgeService;
    private final SkillService skillService;
    private final RunLeaseStore runLeaseStore;
    private final SessionActivityTracker activityTracker;
    private final ModelCredentialService modelCredentialService;
    private final PlatformModelDefaultService platformModelDefaultService;
    private final SessionProgressService sessionProgressService;
    /**
     * 闸门 resume 契约诊断用：懒取框架 pendingInterruptsByThread 反射快照的持有者。
     * {@code ObjectProvider} 而非直接依赖 —— 避免与 harness 组件形成启动期环。
     */
    private final ObjectProvider<
            WakeupInterruptRegistrar> registrarProvider;

    public PlatformAguiRuntimeContextResolver(JwtService jwtService,
                                              SessionWorkspaceManager sessionWorkspaceManager,
                                              SessionOwnerService sessionOwnerService,
                                              SessionAgentRegistry sessionAgentRegistry,
                                              AgentAssemblyService assemblyService,
                                              SessionPermissionReconciler permissionReconciler,
                                              SessionKnowledgeService sessionKnowledgeService,
                                              SkillService skillService,
                                              RunLeaseStore runLeaseStore,
                                              SessionActivityTracker activityTracker,
                                              ModelCredentialService modelCredentialService,
                                              PlatformModelDefaultService platformModelDefaultService,
                                              SessionProgressService
                                                      sessionProgressService,
                                              ObjectProvider<
                                                      WakeupInterruptRegistrar>
                                                      registrarProvider) {
        this.jwtService = jwtService;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.sessionOwnerService = sessionOwnerService;
        this.sessionAgentRegistry = sessionAgentRegistry;
        this.assemblyService = assemblyService;
        this.permissionReconciler = permissionReconciler;
        this.sessionKnowledgeService = sessionKnowledgeService;
        this.skillService = skillService;
        this.runLeaseStore = runLeaseStore;
        this.activityTracker = activityTracker;
        this.modelCredentialService = modelCredentialService;
        this.platformModelDefaultService = platformModelDefaultService;
        this.sessionProgressService = sessionProgressService;
        this.registrarProvider = registrarProvider;
    }

    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
        // 1. 解析登录用户：只认 Authorization: Bearer JWT（不允许匿名）
        String userId = resolveUserId(request);

        // 1.5 对话模型选择（forwardedProps.credential_id + model）：校验凭证可用性
        //（本人或平台共享）并解密，放入 ThreadLocal 供注册表工厂装配时覆盖模型。
        // resolve() 每 run 必经且无条件 set/clear，ThreadLocal 值不会跨 run 泄漏。
        applyRunModelSelection(request, userId);

        // 1.6 对话联网搜索开关（forwardedProps.web_search）：仅显式 false 关闭，
        // 其余一律默认开启。放在模型选择之后——模型选择失败抛 400 时开关尚未置位，
        // ThreadLocal 无残留；本方法自身只做布尔解析，不会抛出。
        applyRunWebSearchSelection(request);

        // 1.7 试运行标记（智能体在线调试 2026-09-24）：forwardedProps.debug=true 时
        // 登记本次 run 走调试草稿装配。标志本身可伪造，但只激活 AgentDebugDraftStore
        // 里「以调用者本人 userId」登记的草稿（写入需 agent:update 权限），无越权面。
        applyRunDebugSelection(request, userId);

        // 2. 会话 ID：AG-UI 的 threadId 即平台 sessionId（前端 main-{uuid}）
        String threadId = request.getInput() == null ? null : request.getInput().getThreadId();
        String sessionId = threadId == null || threadId.isBlank()
                ? "session-anonymous" : threadId;

        // 2.1 闸门提交（resume）请求留痕：AG-UI 契约校验发生在框架
        // AguiRequestProcessor/AguiResumeCoordinator 内，其拒绝路径只返回
        // RUN_STARTED + RUN_ERROR（emitRunFinishedAfterError 默认 false ⇒ 无
        // RUN_FINISHED），且这些事件由框架直接构造、**绕过 AguiAgentAdapter**，
        // 于是既不经过 AguiEventLogEnricher（不落 agui_events），框架自身日志又
        // 全是 debug 级 —— 结果是「闸门提交失败」在后端日志与事件库里双盲，
        // 前端只看到「可能后端异常或重启」的通用提示（2026-10-05 实测）。
        // 这里在 run 入口打一条 INFO：同时列出「前端提交的 resume ids」与
        // 「框架 pending 的 ids」及其差集，下次同类失败一行即可定性。
        logResumeContract(request, sessionId);

        // 2.2 已删除会话终态拒绝（删除不可复活）：会话一旦删除，同一 sessionId
        // 永不可再写。前端删除当前会话时会 replace 跳到新会话，但老 URL 可能被
        // 收藏 / 分享 / 浏览器回退重新打开——此时若放行，用户会在一个「后端已清空
        // 状态行与产物」的 sessionId 上继续对话：上下文为空、产物目录被 run 前的
        // ensureSessionDir 重新建出来，会话既不在列表（列表按 deleted_at IS NULL 过滤）
        // 又在后台继续产出，正是删除语义要禁止的状态。
        // 必须排在第 3 步（ensureSessionDir / record）之前：那两个动作会重建目录与
        // 归属行，事后判断已经晚了。
        if (sessionOwnerService.isDeletedBySession(sessionId)) {
            log.info("Run rejected: session {} was deleted by user (no resurrection)", sessionId);
            throw ApiException.gone(
                    "该会话已被删除，请新建会话");
        }

        // run 互斥守卫（可靠性收口 2026-09-15）：同一会话同时只允许一个活跃 run。
        // 并发 run 会互踩框架会话状态（agent_state CAS、HITL pending interrupt），
        // 此前静默放行正是「补发消息唤醒不可行」结论的根因。已有 run 在跑时拒绝
        // 新 run（409）；LLM 流停滞的僵尸 run 由 RunSilenceDetector 判死并
        // forceEnd 释放计数后自然放行。
        if (sessionId != null && !sessionId.isBlank() && activityTracker.isRunning(sessionId)) {
            log.warn("Concurrent run rejected: session {} already has an active run", sessionId);
            throw ApiException.conflict(
                    "该会话有正在执行的任务，请等待其完成后再试");
        }

        // 3. 运行前托管会话产物目录（幂等创建）
        String agentId = request.getPathAgentId();
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getHeaderAgentId();
        }
        if (agentId != null && !agentId.isBlank()) {
            try {
                sessionWorkspaceManager.ensureSessionDir(agentId, sessionId);
                sessionOwnerService.record(agentId, sessionId, userId);
                // run 级 checkpoint（可靠性收口 2026-09-14）：登记在途租约，
                // 进程死亡后由 RunLeaseReconciler 识别孤儿 run 并驱动恢复。
                runLeaseStore.upsert(userId, sessionId, agentId);
                // 用户主动发起新 run = 停止意图解除（V60）：清除「用户已停止」
                // 抑制标记，此后本 run 及其子任务的完成回执可正常唤醒会话。
                // 失败不阻断 run（标记残留只影响自发唤醒，用户可再 interrupt）。
                sessionProgressService.markStopRequested(agentId, sessionId, userId, false);
                // HITL 超时取消后的封存解封（V62）：本方法是所有 AG-UI 请求（用户
                // 发言 / 闸门提交 / 表单续填）进入的第一站，即「人工发言」的唯一可信
                // 判据——一切自发唤醒源都在服务端，用户不会从那些路径进来。超时取消
                // 封存的会话在此解封，恢复正常推进。
                // 失败不阻断 run（封存残留只禁止自发唤醒，用户可再发一条）。
                sessionProgressService.markAwaitingHumanGate(agentId, sessionId, userId, false);
            } catch (RuntimeException e) {
                log.warn("AG-UI session workspace ensure failed (agent={}): {}", agentId, e.getMessage());
            }
        }

        if (agentId != null && !agentId.isBlank()) {
            sessionAgentRegistry.register(agentId, sessionId);
            // 会话的权限上下文是持久化的、只在新会话播种：平台后加的 ALLOW 规则（如
            // todo_write）对既有会话不生效，会让工具落回框架默认 ASK 并卡死会话。
            // 每次 run 前按策略对账补齐（幂等，无缺失不写库）。
            UUID agentUuid = parseAgentId(agentId);
            var assembled = agentUuid == null ? null : assemblyService.requireAssembledById(agentId);
            if (assembled != null) {
                permissionReconciler.reconcile(
                        agentUuid, assembled.getStateStore(), userId, sessionId);
            }
        }
        RuntimeContext.Builder ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId);
        // 让异步工具（AsyncToolMiddleware）的 wakeup payload 携带所属智能体：
        // 框架在 enqueueWakeup 时读取 ctx.get("agentId")，缺失时 payload 的 agentId 为空，
        // 跨重启回填 session→agent 注册表会丢失该会话的归属信息。
        if (agentId != null && !agentId.isBlank()) {
            ctx.put("agentId", agentId);
        }
        // 会话级知识库关联：工具（KnowledgeSearchTool）与中间件
        // （SessionKnowledgeContextMiddleware）在调用期按类型取回。
        // 无 agentId / 匿名会话时注入空快照，保证工具行为一致。
        if (agentId != null && !agentId.isBlank() && sessionId != null && !sessionId.isBlank()) {
            ctx.put(SessionKnowledgeRef.class,
                    new SessionKnowledgeRef(
                            sessionKnowledgeService.getKnowledgeBaseIds(agentId, sessionId)));
        } else {
            ctx.put(SessionKnowledgeRef.class, SessionKnowledgeRef.NONE);
        }
        // 消息级技能调用（「/ 调用 skill」）：解析 forwardedProps.skill_invocations，
        // 用登录用户重校验可见性（不信任前端）；无效项 warn 丢弃、不 fail run。
        // 无该字段的 run（含历史 resume）一律注入空快照，保证中间件行为一致。
        ctx.put(SessionSkillInvocation.class, resolveSkillInvocations(request, userId));
        return ctx.build();
    }

    /**
     * 闸门提交（携带 {@code resume[]}）请求的可观测留痕。
     *
     * <p>打印「前端提交的 interruptIds」与「框架 pendingInterruptsByThread 的 ids」
     * 及两侧差集。AG-UI 契约要求 {@code resume[]} 与线程上 open interrupts
     * <b>精确集合相等</b>，任一侧多/少一个 id 都会被框架整体拒绝，而拒绝原因只经
     * RUN_ERROR 传给前端、既不落库也不打日志（见调用点注释）。</p>
     *
     * <p>纯旁路：只读反射快照（{@code WakeupInterruptRegistrar} 与本类同为
     * harness/config 层组件，无循环依赖；用 {@code ObjectProvider} 懒取避免启动期
     * 提前实例化），任何异常都吞掉，绝不影响 run。</p>
     */
    private void logResumeContract(AguiRuntimeContextRequest<?> request, String sessionId) {
        try {
            var input = request.getInput();
            if (input == null || !input.hasResume() || input.getResume() == null
                    || input.getResume().isEmpty()) {
                return;
            }
            List<String> submitted = new ArrayList<>();
            for (var resume : input.getResume()) {
                if (resume != null && resume.getInterruptId() != null) {
                    submitted.add(resume.getInterruptId());
                }
            }
            if (submitted.isEmpty()) {
                return;
            }
            var registrar = registrarProvider.getIfAvailable();
            Set<String> frameworkPending = registrar == null
                    ? Set.of()
                    : registrar.snapshotFrameworkInterrupts()
                            .getOrDefault(sessionId, Map.of())
                            .keySet();
            List<String> missing = frameworkPending.stream()
                    .filter(id -> !submitted.contains(id)).toList();
            List<String> unknown = submitted.stream()
                    .filter(id -> !frameworkPending.contains(id)).toList();
            log.info(
                    "AG-UI resume contract: session={}, runId={}, submitted={}, frameworkPending={},"
                            + " missing={}, unknown={}",
                    sessionId, input.getRunId(), submitted, frameworkPending, missing, unknown);
        } catch (Exception e) {
            log.debug("resume contract trace failed: {}", e.toString());
        }
    }

    /** 平台智能体 ID 即 config UUID；非 UUID（历史值）时返回 null。 */
    private static UUID parseAgentId(String agentId) {
        try {
            return UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析消息级技能调用（对话页「/ 调用 skill」）。
     *
     * <p>请求形态：{@code forwardedProps.skill_invocations = [{skill_id, name}, …]}
     * （JSON 反序列化后为 List&lt;Map&gt;；防御性兼容单 Map 与 JSON 字符串形态）。
     * 逐个经 {@link SkillService#resolveVisible} 用登录用户重校验可见性：
     * 不可见 / 不存在 / ID 非法的条目 warn 丢弃，绝不 fail run。</p>
     *
     * <p>防伪造兜底：只有面板选中的技能才会携带该字段——裸「/xxx」文本消息没有
     * skill_invocations，此处直接注入空快照，中间件不会注入任何技能指令。</p>
     */
    private SessionSkillInvocation resolveSkillInvocations(
            AguiRuntimeContextRequest<?> request, String userId) {
        Object raw = request.getInput() == null
                ? null : request.getInput().getForwardedProp("skill_invocations");
        if (raw == null) {
            return SessionSkillInvocation.NONE;
        }
        UUID userUuid = parseUserId(userId);
        if (userUuid == null) {
            log.warn("skill_invocations 携带但登录用户非 UUID，跳过注入: user={}", userId);
            return SessionSkillInvocation.NONE;
        }
        List<Map<String, Object>> entries = extractInvocationEntries(raw);
        if (entries.isEmpty()) {
            return SessionSkillInvocation.NONE;
        }
        Set<UUID> seen = new LinkedHashSet<>();
        List<UUID> requestedIds = new ArrayList<>();
        Map<UUID, String> requestedNames = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            String idStr = entry.get("skill_id") == null ? null : entry.get("skill_id").toString();
            if (idStr == null || idStr.isBlank()) {
                log.warn("skill_invocations 条目缺 skill_id，已忽略: {}", entry);
                continue;
            }
            UUID skillId;
            try {
                skillId = UUID.fromString(idStr.trim());
            } catch (IllegalArgumentException e) {
                log.warn("skill_invocations 条目 skill_id 非法，已忽略: {}", idStr);
                continue;
            }
            if (!seen.add(skillId)) {
                continue;
            }
            requestedIds.add(skillId);
            Object name = entry.get("name");
            requestedNames.put(skillId, name == null ? null : name.toString());
        }
        List<Skill> visible = skillService.resolveVisible(requestedIds, userUuid);
        List<SessionSkillInvocation.Invocation> valid = new ArrayList<>();
        for (Skill skill : visible) {
            if (valid.size() >= MAX_SKILL_INVOCATIONS) {
                log.warn("技能调用超过单条消息上限 {}，多余的已丢弃", MAX_SKILL_INVOCATIONS);
                break;
            }
            valid.add(new SessionSkillInvocation.Invocation(
                    skill.getId(),
                    skill.getName(),
                    skillService.readSkillBody(skill),
                    skill.getResources(),
                    skill.getOriginDir()));
        }
        if (valid.size() < requestedIds.size()) {
            for (UUID id : requestedIds) {
                boolean resolved = valid.stream().anyMatch(v -> v.skillId().equals(id));
                if (!resolved) {
                    log.warn("技能调用不可见/无效，已忽略: skillId={} user={} name={}",
                            id, userId, requestedNames.get(id));
                }
            }
        }
        return valid.isEmpty() ? SessionSkillInvocation.NONE : new SessionSkillInvocation(valid);
    }

    /**
     * 把 forwardedProp 值规整为条目列表。兼容三种形态：
     * List（标准）、单 Map、JSON 字符串（客户端序列化差异兜底）。
     */
    private static List<Map<String, Object>> extractInvocationEntries(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(castStringKeyed(map));
                }
            }
            return out;
        }
        if (raw instanceof Map<?, ?> map) {
            return List.of(castStringKeyed(map));
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                JsonNode node =
                        new ObjectMapper().readTree(s);
                if (node.isArray()) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (JsonNode item : node) {
                        if (item.isObject()) {
                            out.add(castStringKeyed(
                                    new ObjectMapper()
                                            .convertValue(item, Map.class)));
                        }
                    }
                    return out;
                }
                return List.of();
            } catch (Exception e) {
                return List.of();
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeyed(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String key) {
                out.put(key, e.getValue());
            }
        }
        return out;
    }

    /** 登录用户（JWT payload userId）→ UUID；非 UUID（历史账号体系）返回 null。 */
    private static UUID parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 对话页「选择模型」：run 请求 forwardedProps 携带 {@code credential_id + model}
     * （自定义模型名也走此通道）。未携带时优先用<b>平台默认模型</b>（动态设置，
     * 2026-09-22：凭证+启用模型二元组，见 {@link PlatformModelDefaultService}），
     * 未设置/解析失败再回落 {@code agent-platform.model} 配置；携带但凭证不可用/
     * 缺 Key 时直接 400，避免带病 run。
     */
    private void applyRunModelSelection(AguiRuntimeContextRequest<?> request, String userId) {
        try {
            var input = request.getInput();
            Object credentialId = input == null ? null : input.getForwardedProp("credential_id");
            Object model = input == null ? null : input.getForwardedProp("model");
            if (credentialId == null || model == null
                    || credentialId.toString().isBlank() || model.toString().isBlank()) {
                applyPlatformDefaultSelection(userId);
                return;
            }
            applySelection(userId, credentialId.toString().trim(), model.toString().trim());
        } catch (RuntimeException e) {
            // 失败路径同样不能把 ThreadLocal 留给同线程的下一个 run
            RunModelSelection.clear();
            throw e;
        }
    }

    /**
     * 对话页输入框「联网搜索」开关：run 请求 forwardedProps 携带
     * {@code web_search=false} 时关闭本次 run 的平台搜索工具装配
     * （{@code bocha_web_search} 不注册，子智能体工具箱随主 toolkit 深拷贝一并
     * 失去该工具）；未携带 / 值非 false 一律默认开启（「默认联网收集，除非明确
     * 禁止」口径）。解析失败静默按开启处理，绝不因开关字段 fail run。
     */
    private void applyRunWebSearchSelection(AguiRuntimeContextRequest<?> request) {
        Object raw = request.getInput() == null
                ? null : request.getInput().getForwardedProp("web_search");
        if (raw != null && !Boolean.parseBoolean(raw.toString().trim())) {
            RunWebSearchSelection.setDisabled();
        } else {
            RunWebSearchSelection.clear();
        }
    }

    /**
     * 编辑页「试运行」：run 请求 forwardedProps 携带 {@code debug=true} 时登记
     * {@link RunDebugSelection}（登录用户 + 目标智能体），装配期据此用调试草稿
     * 覆盖发布配置。仅布尔解析，绝不因标志异常 fail run。
     */
    private void applyRunDebugSelection(AguiRuntimeContextRequest<?> request, String userId) {
        Object raw = request.getInput() == null
                ? null : request.getInput().getForwardedProp("debug");
        if (raw == null || !Boolean.parseBoolean(raw.toString().trim()) || userId == null) {
            RunDebugSelection.clear();
            return;
        }
        String agentId = request.getPathAgentId() == null || request.getPathAgentId().isBlank()
                ? request.getHeaderAgentId() : request.getPathAgentId();
        UUID agentUuid = parseAgentId(agentId);
        if (agentUuid == null) {
            RunDebugSelection.clear();
            return;
        }
        RunDebugSelection.set(new RunDebugSelection(userId, agentUuid));
    }

    /**
     * 平台默认模型兜底（run 未显式选模型时）：动态默认优先；解析失败
     * （凭证被删 / Key 失效 / scope 变更等）只降级回 {@code agent-platform.model}
     * 配置并告警，<b>不阻断 run</b>——默认模型的可用性不应拖垮全部对话。
     */
    private void applyPlatformDefaultSelection(String userId) {
        var def = platformModelDefaultService.get().orElse(null);
        if (def == null) {
            RunModelSelection.clear();
            return;
        }
        try {
            applySelection(userId, def.credentialId().toString(), def.model());
        } catch (RuntimeException e) {
            log.warn("platform-default-model unavailable ({}:{}): {} — falling back to agent-platform.model",
                    def.credentialId(), def.model(), e.getMessage());
            RunModelSelection.clear();
        }
    }

    /** 构造并登记运行级模型选择（显式选择与平台默认共用；cacheKey 复用覆盖装配缓存）。 */
    private void applySelection(String userId, String credentialId, String modelName) {
        var data = modelCredentialService.resolveDecrypted(userId, credentialId);
        String apiKey = data.get("api_key") == null ? null : data.get("api_key").toString();
        String provider = data.get("type") == null ? null : data.get("type").toString();
        if (provider == null || provider.isBlank()) {
            throw ApiException.badRequest(
                    "所选模型凭证缺少供应商标识");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw ApiException.badRequest(
                    "所选模型凭证未配置 API Key");
        }
        String baseUrl = data.get("base_url") == null ? null : data.get("base_url").toString();
        RunModelSelection.set(new RunModelSelection(
                provider,
                modelName,
                apiKey,
                baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim(),
                credentialId + "|" + modelName + "|" + Integer.toHexString(apiKey.hashCode())));
    }

    private String resolveUserId(AguiRuntimeContextRequest request) {
        // 1. Authorization: Bearer JWT（权威来源）
        String auth = request.firstHeader(JwtInterceptor.AUTHORIZATION_HEADER);
        if (auth != null && !auth.isBlank()) {
            String[] parts = auth.trim().split("\\s+", 2);
            if (parts.length == 2 && "bearer".equalsIgnoreCase(parts[0])) {
                var payload = jwtService.decode(parts[1].trim()).orElse(null);
                if (payload != null && payload.userId() != null && !payload.userId().isBlank()) {
                    return payload.userId();
                }
            }
        }
        // 2. 不允许匿名访问：无 token / token 无效 / token 缺 userId → 401
        //    原先此处还有 X-User-ID 头兜底，已随「身份只认 JWT」一并移除：
        //    该兜底属 fail-open，一旦 JwtAuthFilter 的 urlPatterns 调整即可被用来伪造身份。
        throw ApiException.unauthorized("未登录或登录已过期，请重新登录");
    }
}
