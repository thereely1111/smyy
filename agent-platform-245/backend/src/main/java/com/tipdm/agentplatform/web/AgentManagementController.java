package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.harness.SessionActivityTracker;
import com.tipdm.agentplatform.harness.WakeupInterruptRegistrar;
import com.tipdm.agentplatform.service.AgentConfigService;
import com.tipdm.agentplatform.service.AgentRuntimeService;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.SessionProgressService;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.dto.AgentDtos;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigRequest;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigResponse;
import com.tipdm.agentplatform.web.dto.AgentDtos.AssembleResponse;
import com.tipdm.agentplatform.web.dto.AgentDtos.FileAgentEditView;
import com.tipdm.agentplatform.web.dto.AgentDtos.FileDraftRequest;
import com.tipdm.agentplatform.web.dto.AgentDtos.FilePublishResponse;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.RuntimeDtos;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionInfo;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionTranscriptResponse;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionTurnsResponse;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 管理控制器（Management 层）：保留业务配置管理与会话运维接口。
 *
 * <p>本控制器不处理任何运行时交互（SSE 流式对话、HITL 恢复、表单提交等），
 * 这些运行时功能已迁移至 AG-UI 标准协议，通过框架自动暴露的
 * POST /agui/run/{agentId} 端点提供。</p>
 *
 * <p>保留接口：</p>
 * <ul>
 *   <li>CRUD: GET /, GET /{id}, POST /, PUT /{id}, DELETE /{id}</li>
 *   <li>状态变更: PATCH /{id}/status</li>
 *   <li>装配触发: POST /{id}/assemble</li>
 *   <li>会话运维: GET /{id}/sessions, POST /{id}/sessions/{sessionId}/clear</li>
 * </ul>
 *
 * <p>废弃接口（已迁移至 AG-UI）：</p>
 * <ul>
 *   <li>POST /{id}/chat → POST /agui/run/{id}</li>
 *   <li>POST /{id}/chat/stream → POST /agui/run/{id}</li>
 *   <li>POST /{id}/hitl/resume → AG-UI SubmitToolOutputs 机制</li>
 *   <li>POST /{id}/form/answer → 同上</li>
 *   <li>POST /{id}/hitl/form/answer → 同上</li>
 *   <li>POST /{id}/plan/approve → 发送普通用户消息</li>
 *   <li>POST /{id}/welcome-form/answer → 前端在 messages 中拼接合成消息</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agents")
public class AgentManagementController {

    private static final Logger log =
            LoggerFactory.getLogger(AgentManagementController.class);

    private final AgentConfigService service;
    private final AgentRuntimeService runtimeService;
    private final PendingFormInterruptStore pendingFormStore;
    private final SessionProgressService progressService;
    private final WakeupInterruptRegistrar wakeupInterruptRegistrar;
    private final SessionActivityTracker activityTracker;
    private final SessionAccessGuard accessGuard;

    public AgentManagementController(AgentConfigService service,
                                     AgentRuntimeService runtimeService,
                                     PendingFormInterruptStore pendingFormStore,
                                     SessionProgressService progressService,
                                     WakeupInterruptRegistrar wakeupInterruptRegistrar,
                                     SessionActivityTracker activityTracker,
                                     SessionAccessGuard accessGuard) {
        this.service = service;
        this.runtimeService = runtimeService;
        this.pendingFormStore = pendingFormStore;
        this.progressService = progressService;
        this.wakeupInterruptRegistrar = wakeupInterruptRegistrar;
        this.activityTracker = activityTracker;
        this.accessGuard = accessGuard;
    }

    /**
     * 智能体列表。
     *
     * <p>刻意<b>不加</b>管理端权限码：同一接口是「智能体广场」的数据源
     * （{@code /space} 首页、任务页、对话页与侧边栏会话树都由普通登录用户拉取），
     * 加码会直接让这些页面 403。改为对非管理员脱敏——只摘掉 systemPrompt
     * （编排契约 + 提示词），管理端编辑页凭 {@code agent:menu} 拿完整字段。</p>
     */
    @GetMapping
    public PageResponse<AgentConfigResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID tag,
            @RequestParam(required = false, defaultValue = "false") boolean includeDrafts,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        // 草稿仅管理端可见：广场/对话选择器默认只拿发布态；普通用户即使显式传
        // includeDrafts=true 也按 false 处理，防止草稿从广场口径泄露
        boolean drafts = includeDrafts && accessGuard.isAdmin(request);
        PageResponse<AgentConfigResponse> result = service.list(search, tag, page, size, drafts);
        if (accessGuard.isAdmin(request)) {
            return result;
        }
        return new PageResponse<>(
                result.content().stream()
                        .map(AgentDtos.AgentConfigResponse::redacted)
                        .toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    /**
     * 智能体详情（含 systemPrompt / welcomeForm 等完整配置）。
     *
     * <p>消费方只有管理端编辑页（{@code /admin/agents/:id}，路由守卫 {@code agent:menu}），
     * 故按同一权限码收敛；广场侧用列表接口即可。</p>
     */
    @GetMapping("/{id}")
    @RequirePermission("agent:menu")
    public AgentConfigResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("agent:create")
    public AgentConfigResponse create(@Valid @RequestBody AgentConfigRequest request,
                                      HttpServletRequest httpRequest) {
        return service.create(request, accessGuard.requireUserId(httpRequest));
    }

    @PutMapping("/{id}")
    @RequirePermission("agent:update")
    public AgentConfigResponse update(@PathVariable UUID id, @Valid @RequestBody AgentConfigRequest request,
                                      HttpServletRequest httpRequest) {
        return service.update(id, request, accessGuard.requireUserId(httpRequest));
    }

    @PatchMapping("/{id}/status")
    @RequirePermission("agent:update")
    public AgentConfigResponse updateStatus(@PathVariable UUID id, @RequestBody StatusBody body,
                                            HttpServletRequest httpRequest) {
        return service.updateStatus(id, body.status(), accessGuard.requireUserId(httpRequest));
    }

    /** 发布版本历史（倒序）；管理端编辑页「版本记录」入口。 */
    @GetMapping("/{id}/revisions")
    @RequirePermission("agent:menu")
    public List<AgentDtos.AgentRevisionView> revisions(
            @PathVariable UUID id) {
        return service.listRevisions(id);
    }

    /** 回滚到历史发布版本：恢复为待发布草稿，确认后手动发布（version+1）。 */
    @PostMapping("/{id}/revisions/{version}/rollback")
    @RequirePermission("agent:update")
    public AgentConfigResponse rollback(@PathVariable UUID id, @PathVariable int version) {
        return service.rollback(id, version);
    }

    // ── 目录型智能体在线编辑（2026-09-24） ────────────────────────────
    // 真相源是 workspace/agents/{名}/ 定义目录；本组端点承载「草稿（DB）→
    // 试运行 → 发布（落盘 + 版本存档）」。skills/ 技能树不在在线编辑范围。

    /** 编辑视图：有草稿回草稿，无草稿读磁盘智能体目录。 */
    @GetMapping("/{id}/file-definition")
    @RequirePermission("agent:menu")
    public FileAgentEditView readFileDefinition(@PathVariable UUID id) {
        return service.readFileForEdit(id);
    }

    /** 保存草稿（后写覆盖；不动磁盘）。 */
    @PutMapping("/{id}/file-draft")
    @RequirePermission("agent:update")
    public FileAgentEditView saveFileDraft(@PathVariable UUID id,
                                                     @Valid @RequestBody FileDraftRequest body,
                                                     HttpServletRequest request) {
        return service.saveFileDraft(id, body, accessGuard.requireUserId(request));
    }

    /** 放弃草稿：回到磁盘现状。 */
    @DeleteMapping("/{id}/file-draft")
    @RequirePermission("agent:update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discardFileDraft(@PathVariable UUID id) {
        service.discardFileDraft(id);
    }

    /** 发布：草稿落盘定义目录并生成新版本（version = 历史最大 + 1）。 */
    @PostMapping("/{id}/file-publish")
    @RequirePermission("agent:update")
    public FilePublishResponse publishFileAgent(@PathVariable UUID id,
                                                          HttpServletRequest request) {
        return service.publishFileAgent(id, accessGuard.requireUserId(request));
    }

    // ── 在线调试（2026-09-24） ──────────────────────────────────────────

    /**
     * 登记试运行草稿（编辑页「试运行」按钮）：表单当前内容存入内存仓库（按
     * 登录用户 + 智能体归属），其后该用户携带 forwardedProps.debug=true 的 run
     * 在装配期用这份草稿覆盖发布配置——验证编辑器内容而不影响广场与既有会话。
     */
    @PutMapping("/{id}/debug-draft")
    @RequirePermission("agent:update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveDebugDraft(@PathVariable UUID id,
                               @Valid @RequestBody AgentConfigRequest body,
                               HttpServletRequest request) {
        service.saveDebugDraft(id, body, accessGuard.requireUserId(request));
    }

    /** 清除本人对该智能体的试运行草稿。 */
    @DeleteMapping("/{id}/debug-draft")
    @RequirePermission("agent:update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearDebugDraft(@PathVariable UUID id, HttpServletRequest request) {
        service.clearDebugDraft(id, accessGuard.requireUserId(request));
    }

    /**
     * 目录型智能体免重载：重扫 workspace/agents 并对账（新增注册 / 移除注销 /
     * 全量 evict 装配缓存），改 AGENTS.md 后无需重启即可在下个 run 生效。
     */
    @PostMapping("/reload-files")
    @RequirePermission("agent:update")
    public AgentDtos.FileReloadResponse reloadFileAgents() {
        return service.reloadFileAgents();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("agent:delete")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /**
     * 触发装配（重建工作区定义文件 + 构建 HarnessAgent 实例）。
     *
     * <p>原实现无任何鉴权，同文件 create/update/delete 都有权限码——任意登录用户
     * 可反复触发装配（写盘、重建 workspace、全量文件复制）。补 {@code agent:update}
     * 与"修改智能体"同权（该方法即装配缓存失效后的重建，属配置副作用）。</p>
     */
    @PostMapping("/{id}/assemble")
    @RequirePermission("agent:update")
    public AssembleResponse assemble(@PathVariable UUID id) {
        return service.assemble(id);
    }

    @GetMapping("/{id}/sessions")
    public List<SessionInfo> sessions(@PathVariable UUID id,
                                      @RequestParam(defaultValue = "false") boolean includeArchived,
                                      HttpServletRequest request) {
        var config = service.requireConfigEntity(id);
        // 会话按真实用户分区：当前登录用户（只认 JWT），不允许匿名
        return runtimeService.listSessions(config, requireUserId(request), includeArchived);
    }

    public record TitleBody(String title) { }

    @PatchMapping("/{id}/sessions/{sessionId}/title")
    public void title(@PathVariable UUID id, @PathVariable String sessionId,
                      @RequestBody TitleBody body, HttpServletRequest request) {
        runtimeService.setTitle(service.requireConfigEntity(id), requireUserId(request), sessionId, body.title());
    }

    /**
     * 会话转录：返回后端持久化的消息上下文，供前端同步「子智能体完成 → 后端唤醒续跑」
     * 这类非 AG-UI 推送路径产生的新段落（AG-UI SSE 只推送前端自己发起的 run）。
     *
     * <p>分批加载（2026-09-30）：超长会话全量响应可达十几 MB，前端每次轮询都
     * JSON.parse 整棵消息树会把浏览器内存/主线程打爆。三个可选游标参数：</p>
     * <ul>
     *   <li>{@code after=<msgId>}：增量轮询——只回该 id 之后的消息；</li>
     *   <li>{@code limit=N}：尾部 N 条（打开会话先拉尾部）；</li>
     *   <li>{@code before=<msgId>}（与 limit 连用）：向上翻页——该 id 之前的 N 条。</li>
     * </ul>
     * 响应带 {@code total}（服务端全量条数）与 {@code stale}（游标失效标记）；
     * 无参数时行为不变（全量），旧客户端兼容。
     */
    @GetMapping("/{id}/sessions/{sessionId}/transcript")
    public SessionTranscriptResponse transcript(
            @PathVariable UUID id,
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String before,
            HttpServletRequest request) {
        var config = service.requireConfigEntity(id);
        return runtimeService.transcript(config, requireUserId(request), sessionId,
                limit, after, before);
    }

    /**
     * 会话用户轮次索引：对话区左侧导航条（minimap）的锚点源。
     *
     * <p>转录分批加载后前端只持有尾部若干条消息——导航条若从「已加载消息」推导，
     * 长会话里可用轮次会随加载窗口一起缩水，少到不足两个时整条导航条消失。本端点
     * 独立下发锚点（id + 两端摘要，几十条也只有几 KB），让导航条覆盖整个会话；
     * 锚点 id 与 {@code /transcript} 同源，前端据此定位并可按需向前翻页。</p>
     */
    @GetMapping("/{id}/sessions/{sessionId}/turns")
    public SessionTurnsResponse turns(@PathVariable UUID id, @PathVariable String sessionId,
                                      HttpServletRequest request) {
        var config = service.requireConfigEntity(id);
        return runtimeService.turns(config, requireUserId(request), sessionId);
    }

    /**
     * 会话进度台账（L3）：停滞看护周期性写入的结构化进度快照，供前端/脚本渲染
     * 超长任务进度与「疑似停滞」徽标（nextAction + stallNow）。
     */
    @GetMapping("/{id}/sessions/{sessionId}/progress")
    public RuntimeDtos.SessionProgressResponse progress(
            @PathVariable UUID id,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        service.requireConfigEntity(id);
        String userId = requireUserId(request);
        // 越权防护：进度台账按 (agentId, sessionId) 查询且响应含 lastText（会话尾文本），
        // 原先解析出 userId 却丢弃未用，改 sessionId 即可读他人会话内容。
        accessGuard.requireSessionOwned(id, userId, sessionId, request);
        return progressService.sessionProgress(id.toString(), sessionId);
    }

    /**
     * 会话 run 在跑状态（轻量探针）：{@code running}=true 表示服务端仍有 run
     * 在执行（AG-UI 用户请求、后台续跑、wakeup 轮均计入）。
     *
     * <p>动机：AG-UI SSE 受超时/连接池约束不能无限长挂，前端连接断开后 run
     * 转入后台继续推进（内容经 transcript 同步呈现）——前端此前只靠本地 SSE
     * 生命周期判定「运行中」，断流即误判 idle：会话列表 spinner 消失、输入框
     * 恢复可用，与后台仍在推进的事实矛盾。本端点以 SessionActivityTracker
     * 活跃计数为权威，供前端断流后探测与后台跟随轮询；run 正常结束 / 被中断 /
     * 被静默探测器判死（forceEnd）后返回 false。</p>
     */
    @GetMapping("/{id}/sessions/{sessionId}/run-status")
    public RuntimeDtos.SessionRunStatusResponse runStatus(
            @PathVariable UUID id,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        service.requireConfigEntity(id);
        accessGuard.requireSessionOwned(id, requireUserId(request), sessionId, request);
        return new RuntimeDtos.SessionRunStatusResponse(
                sessionId, activityTracker.isRunning(sessionId));
    }

    // ---------- M11: 未提交表单中断登记（前端刷新后 resume 续填） ----------
    // ui_form_advanced 挂起后，AG-UI 把 pending interrupt（id = {replyId}:{toolCallId}）
    // 存在进程内存，replyId 不出现在 transcript / state store——前端刷新即丢，无法 resume。
    // 前端收到 RUN_FINISHED(interrupt) 时把 interruptId 上报到这里；刷新/回访后查回，
    // 用标准 AG-UI resume 续填。后端重启后框架 pending 与登记表一并清空，
    // 前端 resume 降级为 user-message 新 run，语义自洽（见 PendingFormInterruptStore 注释）。

    /** 前端上报该会话的一条 pending form interrupt（按 toolCallId 覆盖）。 */
    @PutMapping("/{id}/sessions/{sessionId}/form-interrupts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putFormInterrupt(
            @PathVariable UUID id,
            @PathVariable String sessionId,
            @RequestBody FormInterruptBody body,
            HttpServletRequest request) {
        service.requireConfigEntity(id);
        if (body.interruptId() == null || body.toolCallId() == null
                || body.interruptId().isBlank() || body.toolCallId().isBlank()) {
            throw ApiException.badRequest("interruptId 与 toolCallId 必填");
        }
        pendingFormStore.put(id.toString(), requireUserId(request), sessionId,
                new PendingFormInterruptStore.PendingFormInterrupt(
                        body.interruptId(), body.replyId(), body.toolCallId(),
                        body.toolName(), body.toolInput(), body.createdAt()));
    }

    /**
     * 列出该会话全部**待续填**表单中断（无则空数组）。
     *
     * <p>返回前把登记条目回灌进框架 {@code pendingInterruptsByThread}（幂等修复）：
     * 框架映射会被自身 {@code trackPendingInterrupts} 的整体 put 抹掉条目，而 resume
     * 契约要求 resume[] 与映射精确相等——不修复则前端拿着查到的 interruptId 发 resume
     * 仍被拒（{@code Thread has unresolved interrupts}，2026-10-05 闸门3 实测）。</p>
     *
     * <p><b>回灌后再按框架映射对账，只返回存活条目</b>（2026-10-06 修「提交闸门后
     * 立刻刷新，已提交的闸门重新弹出」）。登记表是进程内记账，消费（resume 成功）
     * 不即时删条目——原实现把陈旧条目原样返回，等巡检 60s 后才清，于是提交后
     * 一分钟内刷新，前端查到的仍是「这个闸门还挂着」→ 按「待答」重建 FormCard，
     * 用户看到刚答完的闸门又出现一遍（主编截图复现，闸门「第3章·整章交付确认
     * （超时后重新发起）」重复弹出）。
     * 判定「是否还待答」的权威是框架 {@code pendingInterruptsByThread}——
     * resume 成功即从该集合移除，语义正是「线程上还有 open interrupt 要答」，
     * 且跨刷新、跨进程内记账可靠（与 {@code filterLive} 的其他调用方同口径）。</p>
     *
     * <p>顺序不能颠倒：<b>先对账、再只对存活条目回灌</b>。反序（先全量回灌后
     * 对账）会让 {@code ensureFrameworkRegistration} 把已消费条目重新塞进框架
     * 集合，紧接着 {@code filterLive} 读到这份刚回灌的集合，判定全部存活，
     * 于是过滤永远筛不掉任何东西——2026-10-06 实测「提交后 120ms 刷新」仍然
     * 重弹就是这个顺序造成的。回灌只喂存活项，既保住本次 wakeup 刚登记的
     * 闸门（否则闸门永不渲染），又不会让已消费条目借回灌复活。回灌失败
     * （反射不可达）时 {@code filterLive} 原样返回，行为退化为修复前的
     * 「全量返回」。</p>
     */
    @GetMapping("/{id}/sessions/{sessionId}/form-interrupts")
    public List<PendingFormInterruptStore.PendingFormInterrupt> listFormInterrupts(
            @PathVariable UUID id,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        service.requireConfigEntity(id);
        List<PendingFormInterruptStore.PendingFormInterrupt> entries =
                pendingFormStore.list(id.toString(), requireUserId(request), sessionId);
        List<PendingFormInterruptStore.PendingFormInterrupt> live =
                wakeupInterruptRegistrar.filterLive(sessionId, entries);
        wakeupInterruptRegistrar.ensureFrameworkRegistration(sessionId, live);
        return live;
    }

    /** run 正常收尾后清空该会话登记。 */
    @DeleteMapping("/{id}/sessions/{sessionId}/form-interrupts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearFormInterrupts(
            @PathVariable UUID id,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        service.requireConfigEntity(id);
        pendingFormStore.clear(id.toString(), requireUserId(request), sessionId);
    }

    /**
     * 精确移除一条已提交的表单中断登记（按 toolCallId）。
     *
     * <p>闸门提交成功后前端调用，让登记表立刻反映「这个闸门已答」，不必等
     * {@code HitlTimeoutReaper} 的 60s 巡检。2026-10-06 实测：提交后 120ms 刷新，
     * 登记表仍返回该条目，前端于是按「待答」重建 FormCard → 已答闸门重复弹出。
     * 用全会话 {@code DELETE} 清会误删同会话其它待答闸门，故按 toolCallId 精确移除。</p>
     *
     * <p><b>刻意不动框架 {@code pendingInterruptsByThread}</b>：resume 的 AG-UI
     * 契约要求它与 {@code resume[]} 精确相等，而提交后 resume 可能仍在途；此刻
     * 移除会令契约失配、把正常提交打成「unresolved interrupts」。框架映射由
     * 框架自身在 resume 消费后清理，超时路径另有
     * {@link WakeupInterruptRegistrar#unregisterInterrupts} 兜底。</p>
     */
    @DeleteMapping("/{id}/sessions/{sessionId}/form-interrupts/{toolCallId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFormInterrupt(
            @PathVariable UUID id,
            @PathVariable String sessionId,
            @PathVariable String toolCallId,
            HttpServletRequest request) {
        service.requireConfigEntity(id);
        pendingFormStore.remove(id.toString(), requireUserId(request), sessionId, toolCallId);
    }

    public record FormInterruptBody(String interruptId, String replyId, String toolCallId,
                                    String toolName, String toolInput, String createdAt) {
    }

    /**
     * 归档 / 取消归档会话（幂等）。
     *
     * <p>只把会话从默认列表隐藏，状态与产物目录都保留，取消归档后完全恢复；
     * 不可逆清除走 {@code POST /{id}/sessions/{sessionId}/clear}。</p>
     */
    @PatchMapping("/{id}/sessions/{sessionId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveSession(@PathVariable UUID id,
                               @PathVariable String sessionId,
                               @RequestBody ArchiveBody body,
                               HttpServletRequest request) {
        var config = service.requireConfigEntity(id);
        String userId = requireUserId(request);
        // 越权防护：归档状态归属表按 (agentId, sessionId) 定位，不带 userId，
        // 不校验归属的话任何人拿到 sessionId 就能改他人会话的归档状态。
        accessGuard.requireSessionOwned(id, userId, sessionId, request);
        runtimeService.setArchived(config, userId, sessionId, body.archived());
    }

    public record ArchiveBody(boolean archived) {
    }

    @PostMapping("/{id}/sessions/{sessionId}/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearSession(@PathVariable UUID id, @PathVariable String sessionId, HttpServletRequest request) {
        var config = service.requireConfigEntity(id);
        String userId = requireUserId(request);
        // 越权防护：这是不可逆删除（产物目录 + 归属行 + 状态行）。下游 deleteSession /
        // deleteSessionDir 均按 (agentId, sessionId) 定位、不带 userId，不校验归属的话
        // 任何人拿到 sessionId 即可删除他人会话的全部产物。
        accessGuard.requireSessionOwned(id, userId, sessionId, request);
        runtimeService.clearSession(config, userId, sessionId);
    }

    /**
     * 中断该会话正在运行的 run（停止按钮后端支撑，2026-09-22）。
     *
     * <p>前端停止按钮此前只断本地 SSE，后端 run 会继续跑完（烧 token 且线程锁
     * 不释放）。本端点经 {@code AgentRuntimeService#interruptRun} 触发框架
     * interrupt 真正停掉 run；尽力等待收敛（最多 3s）后返回。</p>
     *
     * <p>幂等：会话无在跑 run（HITL 挂起等表单等场景）时是静默 no-op，
     * 仍返回 202（interrupted=false）。</p>
     */
    @PostMapping("/{id}/sessions/{sessionId}/interrupt")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> interruptSession(@PathVariable UUID id,
                                                          @PathVariable String sessionId,
                                                          HttpServletRequest request) {
        var config = service.requireConfigEntity(id);
        String userId = requireUserId(request);
        // 中断会终止他人会话的运行态，等同写操作，需要归属校验
        accessGuard.requireSessionOwned(id, userId, sessionId, request);
        boolean triggered = runtimeService.interruptRun(config, userId, sessionId);
        // 用户停止语义（V60）：无论本次是否真有在跑 run，只要用户表达了停止意图，
        // 就抑制该会话后续的一切自发唤醒（子任务回执/收件箱补醒/任务补投递/
        // 启动恢复/停滞自检）。否则被中断 pipeline 的子任务完成回执会在数分钟内
        // 事件驱动拉起下一轮，「停止」形同虚设（2026-09-29 实证复活循环）。
        // 清除时机：用户主动发起新 run（PlatformAguiRuntimeContextResolver）。
        try {
            progressService.markStopRequested(id.toString(), sessionId, userId, true);
        } catch (RuntimeException e) {
            log.warn("markStopRequested(true) failed for session {}: {}", sessionId, e.getMessage());
        }
        return Map.of("session_id", sessionId, "interrupted", triggered);
    }

    /**
     * 解卡会话：清除遗留 ASKING/interrupt 状态，但保留会话 ID、归属记录和产物目录。
     */
    @PostMapping("/{id}/sessions/{sessionId}/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recoverSession(@PathVariable UUID id, @PathVariable String sessionId,
                               HttpServletRequest request) {
        var config = service.requireConfigEntity(id);
        String userId = requireUserId(request);
        // 解卡会清除会话运行态，等同他人会话的写操作，同样需要归属校验
        accessGuard.requireSessionOwned(id, userId, sessionId, request);
        runtimeService.recoverSession(config, userId, sessionId);
    }

    /** 全局过滤器已强制登录；此处仅作防御性兜底，避免匿名会话分区被创建/访问。 */
    private static String requireUserId(HttpServletRequest request) {
        String userId = JwtInterceptor.resolveUserId(request);
        if (userId == null || userId.isBlank()) {
            throw ApiException.unauthorized("未登录或登录已过期，请重新登录");
        }
        return userId;
    }

    private void requireActive(AgentConfig config) {
        if (config.getStatus() != AgentStatus.active) {
            throw ApiException.badRequest("Agent 未启用，请先激活");
        }
    }

    public record StatusBody(AgentStatus status) {
    }
}
