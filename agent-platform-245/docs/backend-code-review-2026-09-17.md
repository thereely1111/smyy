# 后端代码审查报告

- **审查时间**：2026-09-17
- **范围**：`backend/src/main/java/com/tipdm/agentplatform/`，214 个 Java 文件 / 28,870 行
- **方法**：分四组并行审查（service / harness / web+repository+config+domain+SQL / channel+notify+横切 grep），之后对全部 P0 及关键 P1 逐条回源核对代码
- **结论**：功能性代码质量不差（无 `System.out`、无裸线程泄漏、SQL 迁移脚本干净、钉钉/飞书签名实现正确），但**安全边界存在系统性缺口**——多处接口"取到了 userId 却没用于过滤"，且 `SessionAccessGuard` 这个正确的归属校验工具在部分控制器被漏用。建议优先处理 P0 的安全四项。

---

## P0 — 必须立即修复

### 1. JWT 签名密钥硬编码默认值 → 全站鉴权可绕过

**位置**：`config/AuthProperties.java:15`、`src/main/resources/application.yml:188`

```java
private String jwtSecretKey = "agent-platform-dev-secret-key-change-me-in-production-0123456789";
```
```yaml
jwt-secret-key: ${AGENT_PLATFORM_JWT_SECRET:agent-platform-dev-secret-key-change-me-in-production-0123456789}
```

代码与配置**两处同时内置**同一个默认值。未通过环境变量覆盖时，任何人可用这个公开在源码里的字符串自签任意 `userId` / `role=admin` 的 access token，绕过 `JwtAuthFilter` 的全部校验。这是本次审查中**投入产出比最高的一个修复**（改一行）。

**修复**：删除两处默认值 → 空串；启动时校验非空且长度 ≥ 32，否则 fail-fast 拒绝启动。

```java
@PostConstruct
void validate() {
    if (jwtSecretKey == null || jwtSecretKey.getBytes(StandardCharsets.UTF_8).length < 32) {
        throw new IllegalStateException("agent-platform.auth.jwt-secret-key 必须通过环境变量配置且不少于 32 字节");
    }
}
```

---

### 2. 公开注册接口允许客户端指定角色 → 匿名提权为 admin

**位置**：`web/UserController.java:37-44` + `web/auth/JwtAuthFilter.java:39` + `service/UserService.java:85`

`/api/users/register` 在 `PUBLIC_PATHS` 中（免登录），却把请求体的 `role` 直接透传：

```java
UserRole role = body.role() == null ? UserRole.END_USER : body.role();
User user = userService.register(body.principal(), body.password(), role, ...);
```
```java
user.setRole(role);   // UserService:85 直接落库
```

攻击者 `POST {"principal":"x","password":"x","role":"ADMIN"}` 即可创建管理员账号，登录后 `RoleInterceptor` 放行全部 `@RequireAdmin` 端点。

**修复**：公开入口强制 `END_USER`（或直接删除 `RegisterRequest.role` 字段）；管理端建户只走 `AuthController` 的 `@RequireAdmin` 版本。

---

### 3. 清除会话接口无归属校验 → 可删除他人会话产物（破坏性越权）

**位置**：`web/AgentManagementController.java:240-244`

```java
@PostMapping("/{id}/sessions/{sessionId}/clear")
public void clearSession(@PathVariable UUID id, @PathVariable String sessionId, HttpServletRequest request) {
    var config = service.requireConfigEntity(id);
    runtimeService.clearSession(config, requireUserId(request), sessionId);   // 只取 userId，未做归属校验
}
```

**项目里其实已经有正确的守卫类** `web/SessionAccessGuard`（归属不符 → 404 且不泄露存在性），且已被 `SessionArtifactController`、`AttachmentController`、`SessionSubagentController` 正确使用——**唯独 `AgentManagementController` 没有引用**（该文件 `SessionAccessGuard|requireOwner` 命中数为 0）。

下游删除动作均不带 userId：
- `SessionOwnerService.deleteSession:181-186` → `deleteByAgentIdAndSessionId(agentId, sessionId)`
- `SessionWorkspaceManager.deleteSessionDir` 同样按 `(agentId, sessionId)` 定位目录

**后果**：任意登录用户拿到他人 `sessionId` 即可删除对方 `runtimes/{agentId}/{sessionId}` 全部产物与归属行；因状态行按 userId 分区反而残留，会留下孤儿会话。同文件的 `archiveSession`（:227-235）存在同样问题。

**修复**：
```java
var config = service.requireConfigEntity(id);
String userId = sessionAccessGuard.requireUserId(request);
sessionAccessGuard.requireOwnedSessionRoot(id, userId, sessionId, request);  // 越权 404
runtimeService.clearSession(config, userId, sessionId);
```
`archiveSession` 同样补上。

---

### 4. 外部知识库 endpoint 未校验 → SSRF 且响应回显

**位置**：`service/ExternalRagClient.java:44-59`、`service/KnowledgeBaseService.java:169`

`kb.setExternalEndpoint(request.externalEndpoint())` 仅校验非空，无 scheme/主机白名单；`search()` 随后 `http.send` 到该地址**并携带解密后的 API Key**，失败时 `truncate(resp.body())` 把响应前 300 字符回显给调用方。可探测内网 / `169.254.169.254` 元数据并读回结果。

**修复**：域名白名单（启动时校验 + 调用前校验），解析 IP 后拒绝 loopback / link-local / 私有网段；错误信息只回状态码，不回显响应体。

> 同类问题：`service/channel/AlertChannelDispatcher.java:247` 的 webhook URL 同样是 `URI.create(url)` 直接发送，`clip(resp.body(), 200)` 回显并写入 `last_error` 返回前端——任意登录用户可借"测试渠道"做内网探测。

---

### 5. 渠道凭据明文入库

**位置**：`domain/ChannelCredential.java:32-34`

```java
/** 凭据值（TEXT）。密钥，永不回显。 */
@Column(name = "cred_value", nullable = false, columnDefinition = "TEXT")
private String credValue = "";
```

存的是钉钉/飞书加签 secret 与 webhook Bearer token，**明文**。而同一仓库内 `ExternalApiKeyCipher`（AES-GCM）已在保护外部 RAG 的 API Key——两套标准。做到了"不回显"，但没做到"不裸奔"，DB 一次拖库即全量用户 IM 凭据泄露。

**修复**：`ChannelService.replaceCredentials`（:202）写入前 `cipher.encrypt()`，`AlertChannelDispatcher.loadCredentials` 读出后解密。

---

### 6. HITL 闸门护栏装错了工具 → 模型可用 ui_card 冒充审批通过

**位置**：`service/UiTools.java:63-80`（`formTool`）vs `:114-121`（`cardTool`）

那段"防止模型用只读卡片冒充审批闸门"的守卫，被写在了 **`formTool()`** 里（报错文案与 `name` 却都是 `ui_card`）：

```java
// formTool 的 callAsync 内
if (input.contains("闸门") || input.contains("审批") || ... || input.contains("approval")) {
    return ... .name(CARD_TOOL)   // ← name 用的是 CARD_TOOL
        .output("[ui_card] rejected: approval decisions must use ui_gate; no approval was recorded")
        .state(ToolResultState.ERROR);
}
```

而**真正需要它的 `cardTool()` 的 `callAsync` 完全没有校验**，直接返回：

```java
return Mono.just(ToolResultBlock.builder()
        .name(CARD_TOOL).output("[ui_card] rendered").state(ToolResultState.SUCCESS).build());
```

**后果**：这正是注释里声称要防的事故——模型调用 `ui_card` 输出"已通过审批"即拿到 `SUCCESS`，HITL 闸门被绕过。建议结合记忆中的「HITL 恢复后模型假放行」遗留问题一并排查，这可能是其诱因之一。

**修复**：把守卫整体搬到 `cardTool()` 的 `callAsync`（`name` 用 `CARD_TOOL`），`formTool` 保留原 `RUNNING` 逻辑。

---

## P1 — 明确 bug / 高风险

### 并发与运行时状态

| # | 位置 | 问题 |
|---|---|---|
| 7 | `harness/SessionActivityTracker.java:17,25-30` | **计数竞态**：`computeIfAbsent` 拿到 `AtomicInteger` **之后**才 `incrementAndGet()`；若这一瞬 `end()` 把它 `<=0` 并 `remove`，本次 +1 落在已脱离 map 的对象上 → 会话永久 `isRunning=false`。后果是 AG-UI 409 互斥守卫放行第二个 run（即"同会话双流程"），且后续 `end()` 静默 no-op。改：`compute()` 内原子自增，或按 runId 维护 `Set` 用 token 移除 |
| 8 | `AguiWakeupTarget.java:262` + `RunSilenceDetector.java:93` | **run 卡在首个事件之前时无人能救**：`streamEvents` 无 `timeout`；`eventClock.touch` 只在 `doOnNext` 发生，而探测器遍历 `eventClock.snapshot()`——**没条目就跳过**。于是 run 卡住 → tracker 仍 running → 租约对账只做心跳 → 所有 wakeup 被永久跳过 → 会话死锁且零告警。改：`streamEvents(...).timeout(...)`；探测器改为遍历 tracker 报告的活跃会话并用 begin 时刻兜底 |
| 9 | `AguiWakeupTarget.java:207/306` | `countLiveFormInterrupts` 直接读 `pendingFormInterruptStore.listBySession`，**没走** `WakeupInterruptRegistrar.filterLive`（:217 那条与框架 pending 对账的逻辑）。一条永不过期的陈旧登记即让"陈旧闸门恢复"分支永不进入——注释里记的"28 次 deferring 死锁"。改：`filterLive(sessionId, listBySession(...)).size()` |
| 10 | `WakeupInterruptRegistrar.java:177-183` | `pending.get()` → 建新 map → `pending.put()`，read-modify-write 非原子，并发登记两个闸门丢一个，resume 报 `must cover all open interrupts`。改 `pending.compute(...)` |
| 11 | `WakeupInterruptRegistrar.java:150` | `toolUse` 未判空（113 行取、144/147 行都判了，150 行没判）→ NPE 从 `AguiWakeupTarget:271` 的 `doOnNext` 冒出，整次 wakeup 判失败且后续闸门全不登记。补判空 + `registerSuspendedGates` 整体 try/catch |
| 12 | `SessionActivityMiddleware.java:99-117` | `recoverStaleHitl` 靠错误**子串**匹配就 `clearContext + stateStore.delete + clearStateCache`，**永久抹掉整个会话历史，成功路径零日志**。必须加 `log.warn`（带 sessionId/userId/原始 error）并在删除前落审计 |
| 13 | `SessionEventClock` | 无引用计数：`SessionActivityMiddleware:64` 的 `doFinally` 里先结束的并发 run 会 `clear(sessionId)` 擦掉仍在跑那方的时钟 → 探测器对其失明 |
| 14 | `config` / `AgentPlatformApplication.java:8` | 只有 `@EnableScheduling`，无自定义 TaskScheduler。harness 里 7 个 `@Scheduled` + `RuntimeWorkspaceGc` 全挤 1 个线程；`SubagentSessionMaintenance.sweep()`（全量 owner × 全量 session）一慢，30s 周期的 `InboxWatchdogWakeupService`、`ResumeCoordinatorDeadlockCleaner` 全被饿死。配 `spring.task.scheduling.pool.size` 或注入 `ThreadPoolTaskScheduler` |
| 15 | `TaskDeliveryWatchdog.java:145-163` | 注释写"先确保 hint 落盘"，实际 `inboxPush(...).subscribe()` 是 fire-and-forget，162 行 `lastSweepAt.put` 与 163 行 `enqueueWakeup` 立即执行 → 竞态下 wakeup 先到、drain 空 inbox、整轮白跑。改：串进 `flatMap/then` |
| 16 | `AguiWakeupTarget.java:171-225` | `ensureSessionDir`、`record`、`permissionReconciler.reconcile`（读写库）、`hasPendingHitl` 全在返回 Mono **之前**同步执行——调用方不订阅副作用照样发生。整体包进 `Mono.defer` |

### 性能与资源

| # | 位置 | 问题 |
|---|---|---|
| 17 | `service/AgentAssemblyService.java:371` | `public synchronized HarnessAgent assemble(...)` **全局锁**：装配含 3 次 DB 查询 + `createDirectories` + 全量文件复制 + `build()`，所有智能体、所有请求串行。冷启动/缓存失效时整平台对话互相阻塞。改 `agents.computeIfAbsent(id, k -> doAssemble(config))` 或按 agentId 分段锁 |
| 18 | `AgentRuntimeService.java:407-408,537` | `archivedTranscriptCache`（`ConcurrentHashMap<Path,...>`）**只 put 不清理**，value 是整份解析后的消息列表；`doParseArchivedTranscript` 用 `readAllLines` 整文件进内存。前端每 5s 轮询，长会话 jsonl 数十 MB → OOM。换 Caffeine `maximumSize(200).expireAfterAccess(10m)`；解析改 `Files.lines` 流式 + 行数上限 |
| 19 | `SkillRepositoryService.java:725-749` | clawhub 分页 `while (cards.size() < CLAWHUB_MAX_BROWSE)` 只在 `nextCursor == null` 时退出；上游返回相同 cursor 且该页 0 条 → **无限请求**。另 `detail():184` 的 `cache = cache.stream()...toList()` 是对 volatile 字段的非原子读改写，并发丢更新。改：记录上轮 cursor 相同则 break；cache 用 `AtomicReference` |
| 20 | `SessionProgressService.java:540-548` | `hasActiveLease` 每次 `runLeaseStore.findAll()` 再内存 `anyMatch`，看护每分钟对全部活跃会话跑一遍 → N 次全表扫描。改 `existsBySessionId(sessionId)` 或循环外建 `Set` |
| 21 | `KnowledgeTextRetrievalService.java:54-60` / `KnowledgeIngestionService.java:44,80-84` | 检索对每文件 `readAllLines` 无大小上限；摄入在 `@Transactional` 内对每个分块串行远程 embed，数百块占连接数分钟。改：加 8MB 上限 + 流式；摄入去方法级事务、批量 embed、分批提交 |
| 22 | `KnowledgeBaseService.java:442-459` | `CompletableFuture.runAsync` 用**公共 ForkJoinPool** 跑阻塞 IO（远程 embedding + 多次 DB 写），并发几个文档即打满公共池，连带拖垮 `Schedulers.parallel()`。改：注入专用 `ThreadPoolExecutor` |
| 23 | `AuthService.java:90` / `UserService.java:57` | 事务内做远程 HTTP（login 串行调 token/userinfo/permissions；register 调外部鉴权）。外部抖动时长时间持连接，连接池迅速耗尽。去方法级 `@Transactional`，只给落库加 |
| 24 | `SessionProgressService.java:270,388-394` | `@Transactional scanAndGuard()` 内起异步 run（`agent.streamEvents(...).subscribe(...)`），run 与事务生命周期完全脱钩。去掉方法级事务，补醒改 `afterCommit` 或独立事件发布 |

### 安全（P1）

| # | 位置 | 问题 |
|---|---|---|
| 25 | `web/SessionExtensionController.java:55-60,91-98,103-109` | 会话扩展读/删单条/删整条**均无归属校验**，连 `HttpServletRequest` 都没接；`SessionExtensionService` 查询条件不含 userId。`agent_session_extension_entries` 有 `ON DELETE CASCADE`，一次请求可清空他人会话扩展及全部子表。复用 `SessionKnowledgeController.java:75` 的 `requireOwnerOrCreate` 模式 |
| 26 | `web/AgentManagementController.java:160-168` | `progress` 调了 `requireUserId(request)` 但**返回值被丢弃**，`sessionProgress` 只按 `agentId+sessionId` 查，返回 `lastText` 等他人会话内容。同文件 `transcript`/`title`/`archive` 都正确传了 userId，唯独这里漏 |
| 27 | `web/UserController.java:77` + `UserService.java:155,200` | `lastSendTimeMap` 以攻击者可控的手机号/邮箱为 key，**只 put 不淘汰**；而发验证码是 `PUBLIC_PATHS` 公开路径 → 匿名可用不同号码灌入至 OOM。换 Caffeine（maxSize + TTL） |
| 28 | `web/AgentManagementController.java:121-124` | `POST /api/agents/{id}/assemble` **无任何鉴权**（同文件 create/update/delete 都有 `@RequirePermission`），任意登录用户可反复触发装配（写盘、重建 workspace）。补 `@RequirePermission("agent:update")` |
| 29 | `web/AgentManagementController.java:82-93` | 智能体列表/详情无权限校验，且回显 `workspacePath`（宿主绝对路径 `/Volumes/.../workspace/agents/{id}`）。加权限码；`workspacePath` 移除或改相对路径 |
| 30 | `web/auth/RoleInterceptor.java:46-50`、`KnowledgeBaseController.java:160-177`、`GroupController.java:77-94` | **header 兜底身份**：无 JWT 时信 `X-User-Role`/`X-User-Id`（role 缺省 `developer`）。当前靠 `JwtAuthFilter` 写 attribute 兜住，属"单点失效即身份伪造"——任何绕过过滤器的新路径可直接伪造。删除 header 兜底，解析不出 JWT 身份一律 401 |
| 31 | `web/SessionExtensionController.java:119-120` | `Integer.parseInt` 裸调 → `?page=abc` 抛 `NumberFormatException` 兜成 500；`size` 无上限。改 `@RequestParam(defaultValue="0") int page` + `Math.min(size, 200)` |
| 32 | `web/SkillRepositoryController.java:39` | `POST /api/skill-repository/sync` 无权限码，任意登录用户可强制触发对外部仓库的出站请求（git fetch / HTTP 索引），形成 SSRF 放大与配额消耗面 |
| 33 | `service/EmbeddingClient.java:55-65` | 远程 embedding 失败**静默降级为哈希向量**（词频哈希）。key 失效/限流时整库向量变成无语义哈希，检索结果看着正常实则全错，且每块一条 WARN 刷爆日志。改：失败即抛并置 error，或仅在显式开启时降级且每文档只告警一次 |

### 数据访问与可靠性

| # | 位置 | 问题 |
|---|---|---|
| 34 | `repository/AgentSessionOwnerRepository.java:26,44,49,68` | 4 个 `@Modifying` native 修改查询**未标 `@Transactional`**，目前全靠 `SessionOwnerService` 外层事务兜底；`harness/` 侧任何无事务调用点即抛 `TransactionRequiredException`。同包 `AgentSessionProgressRepository:31`、`PlatformNotificationRepository:56` 都已标注，属遗漏（项目已踩过此坑） |
| 35 | `notify/AlertNotifier.java:143-157` | 未读抑制**无时间上限**：用户一直不读铃铛，同一 alertKey 三天后再出问题也永远不再提醒，30 分钟冷却窗口实际永远走不到。加"未读抑制最长时效"（如 24h）后降级为冷却判定 |
| 36 | `notify/AlertNotifier.java:115-121` | 扇出同步阻塞：`publish()` 串行 3 次 DB 查询，被看护线程周期性调用，DB 抖动时整体变慢。落库后把扇出丢独立线程池 |
| 37 | `AlertChannelDispatcher.java:76-78,175` | HttpClient 未注入 Executor（JDK 默认无界），无法限流也无法 shutdown；失败无重试、不区分可重试（超时/5xx）与终态（4xx/签名错），一次抖动即永久丢告警。注入固定线程池 + 渠道令牌桶 + 延迟重试 |
| 38 | `AlertChannelDispatcher.java:134-155` | `sendTest` 在 HTTP 请求线程里同步 `httpClient.send()`，最坏占满 Tomcat 线程约 10s。改 `sendAsync` + `get(timeout)` 或 202 + 轮询 |
| 39 | `AlertChannelDispatcher.java:176` | `orTimeout(TIMEOUT_SECONDS + 10)` 冗余：`HttpRequest.timeout(10s)`（:248）已覆盖连接+读取全程。删外层，保留一处常量 |

---

## P2 — 该改但不紧急

- **`AgentAssemblyService.java:1131-1147`**：`evict/evictAll` 不同步清理 `policies`，`policyPermissions()` 会给已删除智能体返回陈旧策略，`SessionPermissionReconciler` 据此写回错误权限。
- **无界内存结构 4 处**：`PendingFormInterruptStore:33`、`AguiEventPersistenceService:65,68`（`sequences`/`ownerCache` 永不失效）、`UserService:155`、`AuthService:76`（`pkceStates` 使 OAuth 登录**无法多实例部署**，建议迁 Redis）。
- **`AguiEventPersistenceService.java:105,115`**：`ConcurrentLinkedQueue.size()` 是 O(n)，每个事件 capture 两次 → 整体 O(n²)。用 `AtomicInteger` 自计数。
- **冷却 Map 只增不减 4 处**：`TaskDeliveryWatchdog:58`、`InboxWatchdogWakeupService:60`、`RunSilenceDetector:66,69`。
- **`web/dto/KnowledgeBaseDtos.java:42`**：`embeddingModelConfig` 原样回显，知识库被授权给他人后，创建者写入的 embedding `apiKey` 会随之泄露。出参过滤含 `key/secret/token` 的字段。
- **`SessionArtifactController.java:237-252`**：`readAsText` 默认跟随符号链接，`validateRelPath:275` 只拦字面 `.`/`..`；会话目录内若有 symlink（模型 `execute` 可创建），预览即可跨出目录读宿主任意文件。追加 `toRealPath()` 前缀校验。
- **`KnowledgeBaseController.java:87-98`**：`uploadDocument` 只判 `isEmpty()`，无大小/类型白名单（对比 `AttachmentController:57` 有 20MB + 扩展名校验）。
- **`AttachmentController:122,155,168` / `KnowledgeBaseController:155` / `KnowledgeStorageService:58,63,73`**：异常信息（含 IO 路径）直接回显前端，与 `GlobalExceptionHandler` 统一脱敏口径不一致。
- **`GlobalExceptionHandler`**：缺 `MissingServletRequestParameterException` / `ConstraintViolationException` 处理器，必填参数缺失落到兜底 500，掩盖"调用方传错参数"。
- **`AgentAssemblyService.java:508-511`**：`Paths.get(workspaceRoot)` 未 `.normalize()`（`SessionWorkspaceManager:213`、`WorkspaceGenerator:48` 都做了），`startsWith` 前缀校验依赖未归一化 root。
- **`UserService.java:397`**：`RSA/ECB/PKCS1Padding`，建议改 `OAEPWithSHA-256AndMGF1Padding` 并校验明文长度。
- **`AgentRuntimeService.java:825`**：空 catch 吞业务异常，建议至少 debug 日志。
- **`SessionRunStopper.java:57`**：请求线程内 `Thread.sleep` 轮询最长 3s。
- **`ResumeCoordinatorDeadlockCleaner:82`**：注释说"主动调 `finishRun`"，实际只 `activeRuns.remove()`，绕过 pendingInterrupts 联动清理。
- **`StartupStaleTaskCleaner:95`**：`p.toString().contains("/tasks/")` 绑死 `/` 分隔符，Windows 失效。
- **`SubagentSessionMaintenance:125`**：`computeIfAbsent(agentId, assemblyService::requireAssembledById)` 在 CHM bin 锁内做重装配，并发 sweep 放大锁竞争。
- **`AguiWakeupTarget:239-241`**：`staleGateRecoveryAttempted.add` 在 run 发起前消费，run 失败不回滚，恢复额度白花。

---

## 已核对为"正确"、无需改动的部分

审查中特意核对了若干容易出错但本项目实现正确的地方，避免误报：

- **钉钉签名**（`AlertChannelDispatcher:303-306`）：`HmacSHA256(key=secret, data="ts\nsecret")` → base64 → urlencode，与官方一致；毫秒级 ts ✅
- **飞书签名**（:309-312）：`HmacSHA256(key="ts\nsecret", data="")` → base64（不 urlencode），与官方一致；秒级 ts ✅
- **HTTP 超时**：`HttpRequest.timeout(10s)` + `connectTimeout(5s)` 均已设置，**不存在缺超时导致线程挂死**
- **凭据回显**：`ChannelService.toRecord`（:154）确实不返回 credentials ✅
- **会话产物/附件/子任务视图**：走 `SessionAccessGuard.requireOwned`，越权按 404 且 **不泄露存在性**，写法规范 ✅
- **SQL 迁移脚本**（V1~V44，39 个）：无 DROP/TRUNCATE；唯一 DELETE 是 V36 带 `WHERE username='admin' AND auth_type='password'` 的条件删除；高频查询字段（`user_id`、`session_id`、`(agent_id,session_id)`、`(user_id,alert_key,created_at)`）均已建索引；外键均带 `ON DELETE CASCADE` ✅
- **横切反模式**：`System.out.print` / `printStackTrace()` **0 处**；`@Async` 0 处（不存在自调用失效）；`new Thread(` 3 处均 `setDaemon(true)` 且配对 `@PreDestroy` shutdown；完全空的 catch 块 5 处中 4 处是 UUID 解析 `ignored`，语义正确 ✅

---

## 建议修复顺序

1. **P0-1（JWT 密钥）** — 一行改动，堵住全站鉴权绕过
2. **P0-2（注册提权）** — 一行改动，堵住匿名提权
3. **P0-3（clearSession 越权）** — 接入已有的 `SessionAccessGuard`，连同 `archiveSession`
4. **P0-6（闸门装错工具）** — 移动守卫到 `cardTool`，排查是否即"HITL 假放行"根因
5. **P0-4 / P0-5（SSRF / 凭据明文）** — 加白名单 + 复用已有 `ExternalApiKeyCipher`
6. **P1-7 / P1-8（并发竞态 / run 无超时）** — 对应已知的"同会话双流程"与"会话死锁"顽疾
7. **P1-17 / P1-18（全局锁 / 无界缓存）** — 线上稳定性与 OOM 风险
8. 其余 P1 按业务影响排期，P2 并入日常迭代

---

## 修复状态（2026-09-17 收口）

**P0 6/6 已修**：JWT 密钥 fail-fast、公开注册强制 END_USER、会话接口接入
`SessionAccessGuard`（clear/archive/recover/progress）、SSRF 出网守卫、
渠道凭据 AES-GCM 加密、闸门护栏从 `ui_form` 移到 `ui_card`。

**P1 33/33 已修**（含 #25/#26/#30 的会话扩展与身份头兜底）。
**P2 修复 20 项**（含新增发现的 SkillRepositoryController 无鉴权等）。

### 本轮新增/更正的结论

1. **审核报告一处误报（已更正）**：`workspacePath` 回显的是**相对路径**
   （`WorkspaceGenerator:63` / `FileSystemAgentLoader:113,195` 均写相对值），
   不是宿主绝对路径，无需脱敏。
2. **agent 列表接口不能加权限码**：`GET /api/agents` 是「智能体广场」数据源，
   `/space` 首页、任务页、对话页与侧边栏会话树对**所有登录用户**都要拉取。
   加管理端码会直接让这些页面 403 —— 改为对非管理员脱敏（只摘 `systemPrompt`）。
3. **P1-8（run 卡在首个事件前）的真实成因**：不是缺一个 Reactor timeout，
   而是 `SessionEventClock` 只在首个事件到达时建条目，导致这种 run 不在探测器
   快照里。修复方式是在 run 起跑即 `begin()` 登记；**刻意不叠加** `timeout()`，
   因为框架取消链在 `BufferedReader` 锁上会死锁（超时能发 `onError` 却取消不了上游）。
4. **闸门存活判定必须过框架对账**（P1-9）：平台登记表只在
   `RUN_FINISHED(success)` 时清空，一条永不过期的残留条目就让
   `liveForms` 恒 > 0、「陈旧闸门恢复」分支永不进入 —— 这正是 28 次
   `deferring` 死锁的形态。已加两个守护用例。
5. **`UserService` 的 `RSA/ECB/PKCS1Padding` 未改**：加密参数必须与外部统一认证
   服务（9623）两侧一致，单方面改 OAEP 会导致注册/登录全挂。需与对方确认后同改。

### 测试

`mvn -o test`：**225 项全绿**（原 223，本轮新增 2 个守护用例）。
前端 `tsc -b && vite build` 通过；已核对产物中 `X-User-Role` 出现 0 次、
`Authorization` 正常，确认前端身份头兜底移除真的落进了 bundle。
