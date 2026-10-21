# 后端代码审查报告（第二轮）

- **审查时间**：2026-09-17 12:40 起
- **审查基线**：`develop` @ `c049893`（工作区干净，后端全部已提交）
- **代码规模**：176 个 Java 文件 / 24,723 行
- **审查方式**：5 路并行独立审查 + **回源核对**（所有 P0/P1 结论与全部误报判定均逐条读原始代码确认）
- **与第一轮的关系**：第一轮（`docs/backend-code-review-2026-09-17.md`）的 P0 6 / P1 33 / P2 20 已修复。本轮**不重复上报已修项**，只做两件事：核验修复是否真落地 + 找新问题。

---

## 一、总览

| 等级 | 本轮新增 | 说明 |
|---|---|---|
| P0 | **1** | OAuth userinfo 兜底伪造固定身份（默认配置即触发，身份隔离完全失效） |
| P1 | **7** | 详见下文 |
| P2 | **14** | 详见下文 |
| 误报更正 | **3** | 子审查提出的 3 条经回源核对不成立，已剔除 |

**第一轮修复核验结果：P0 6/6、P1 33/33、P2 20/20 全部确认落地，无一回退。**（核验明细见第四节）

---

## 二、P0

### P0-1 OAuth userinfo 未配置时伪造固定 `user_id`，导致全平台 OAuth 用户合并为同一账号

> ✅ **已于 2026-09-17 13:50 修复（`aa26602`）** —— 改为 fail-fast，见 §九。下面保留原始分析。
>
> 补充核实（13:20）：`application.yml:217` 为 `userinfo-url` 配了**非空默认值** `http://<内网地址>:9623/api/user/info`，故在加载该 yml 的部署里原伪造分支**不可达**，实际严重度曾降为 P2；触发条件仅剩「显式把 `AGENT_PLATFORM_OAUTH_USERINFO_URL` 置空」。修复后该情形改为**启动失败**，比降级更稳妥。

- **位置**：`service/AuthService.java:393-397`（兜底）、`service/AuthService.java:418-422`（消费）、`config/AuthProperties.java`（默认值）
- **证据**：

```java
// AuthService.java:393-397
private Map<String, Object> fetchUserinfo(String accessToken) {
    String userinfoUrl = properties.getOauth().getUserinfoUrl();
    if (userinfoUrl == null || userinfoUrl.isBlank()) {
        return Map.of("user_id", "oauth-user", "username", "oauth_user");   // ← 合成固定身份
    }
```

```java
// AuthService.java:418-422 —— 该 user_id 直接用于账号 upsert
String oauthUserId = firstNonBlank(str(info.get("user_id")), str(info.get("userId")),
        str(info.get("sub")), str(info.get("id")));
if (oauthUserId == null) {
    throw ApiException.badRequest("OAuth userinfo 缺少用户标识");
}
```

- **影响**：`userinfoUrl` 在 `AuthProperties` 中默认为空。**未配置时**，每个 OAuth 登录者拿到同一个 `user_id="oauth-user"` + `provider="default"`，`syncOauthUser` 按 `(oauthUserId, provider)` 查到**同一条**本地 users 记录 → 所有 OAuth 用户共享一个本地账号：
  - 会话归属（`agent_session_owner.user_id`）相同 → **能看到并操作他人的会话、产物、扩展数据**；
  - 知识库、群组、通知全部串号；
  - 且默认角色是 `DEVELOPER`，不是最低权限。
- **注意**：第 420-421 行有 `oauthUserId == null` 的校验，但那是**下游**校验——兜底分支（393-397）已经保证不可能为 null，所以该校验永远拦不住。**校验点被上游兜底架空了。**
- **判定依据**：这是「默认配置即触发 + 后果为身份隔离完全失效」。对照同项目 `JwtService`（空 secret 直接抛异常 fail-closed）的既有标准，这里应当 fail-fast 而非伪造身份。
- **待你确认**：若生产部署**确实已配置** `userinfoUrl`，则本条不可触发，降级为 P2（代码健壮性）。请核对部署配置。
- **建议修法**：删除 393-397 的伪造分支，改为抛 `ApiException.internal("OAuth userinfoUrl 未配置")`；并在 `AuthProperties` 上为 `oauth.userinfoUrl` 加 `@NotBlank`（仅在 `oauth.enabled=true` 时生效的校验）。

---

## 三、P1

### P1-1 `RuntimeWorkspaceGc` 会把共享目录 `.harness` 当作会话目录删除，导致整个 Agent 失能

- **位置**：`service/RuntimeWorkspaceGc.java:54-63`
- **证据**：

```java
// RuntimeWorkspaceGc.java:54-63
try (Stream<Path> agentLevel = Files.list(runtimes)) {
    for (Path agentDir : agentLevel.filter(Files::isDirectory).toList()) {
        try (Stream<Path> sessionLevel = Files.list(agentDir)) {
            for (Path sessionDir : sessionLevel.filter(Files::isDirectory).toList()) {
                if (isExpired(sessionDir, cutoff)) {     // ← 只看 mtime
                    deleteRecursively(sessionDir);
```

- **影响**：`runtimes/{agentId}/` 下**不只**有会话目录，还有 `SessionWorkspaceManager.java:150` 明确使用的共享目录 `.harness`（装配期写入的技能副本与 harness 工作区）。`.harness` 通常只在首次装配/技能变更时被写入，mtime 很旧 → 一旦超过 `runtime-gc-max-age-days`（默认 30）就被整目录删除，**该 Agent 所有会话的运行环境随之失效**。
- **触发条件**：GC 默认关闭（`agent-platform.runtime-gc-enabled=false`），但**这是运维按需开启的功能**，开启即中招。
- **建议修法**：跳过 `.` 开头的目录（`!p.getFileName().toString().startsWith(".")`）；更稳妥是改为白名单——只删匹配 `sessionId` 形态（UUID/`wb-*`/`sub-*`）的目录。

### P1-2 `redacted()` 只摘了 `systemPrompt`，未脱敏 `subagents[].prompt`（第一轮修复不完整）

- **位置**：`web/dto/AgentDtos.java:105-110`
- **证据**：

```java
// AgentDtos.java:105-110 —— systemPrompt 置 null，但 subagents 原样透传
public AgentConfigResponse redacted() {
    return new AgentConfigResponse(id, name, description, null, model, status,
            skillIds, toolIds, subagents, launchTemplates, planEnabled, skillSedimentation,
            workspacePath, fileBased, definition, welcomeForm, form, formTrigger,
            createdAt, updatedAt);
}
```

```java
// AgentDtos.java:23-33 —— SubagentInline 含 prompt 字段
public record SubagentInline(String name, String description, String prompt, String model, ...)
```

- **影响**：`GET /api/agents` 是**智能体广场**的数据源，对所有登录用户开放（刻意不加权限码）。第一轮把它改为对非管理员调用 `redacted()`，但只摘了 `systemPrompt`——子 Agent 的 `prompt`（同样是编排提示词/系统提示词，敏感级别相同）仍原样返回。**等于前门关了后门。**
- **建议修法**：`redacted()` 里同步清空 `subagents` 的 `prompt`（保留 `name`/`description` 供广场展示），或整个 `subagents` 置空并改为单独的 `GET /{id}` 管理端接口返回。

### P1-3 知识库 `embeddingModelConfig` / `externalExtra` 明文落库，与 `externalApiKeyEnc` 两套标准

- **位置**：`service/KnowledgeBaseService.java:155`、`:171`、`:231-233`、`:251`；`domain/KnowledgeBase.java:37-38,58-59`
- **证据**：

```java
// KnowledgeBaseService.java:170-171 —— 同一实体内两套标准
kb.setExternalApiKeyEnc(externalApiKeyCipher.encrypt(request.externalApiKey()));  // 加密落库
kb.setExternalExtra(request.externalExtra());                                     // 明文落库

// KnowledgeBaseService.java:155
kb.setEmbeddingModelConfig(request.embeddingModelConfig());                        // 明文落库
```

- **影响**：`redactEmbeddingConfig()`（`:639-640`）自己承认 embedding 配置里可能含 `key`/`secret`/`token`/`password` 字段——但脱敏**只作用于出参**，落库是明文 jsonb。数据库或备份泄露即暴露第三方 embedding 提供方密钥；`externalExtra` 同理。
- **注意**：第一轮补的 `redactEmbeddingConfig` / `mergeEmbeddingConfigSecrets` 本身实现正确（已核验 `:639-640`、`:665-670`），缺的是**存储侧加密**。
- **建议修法**：对 `embeddingModelConfig` 与 `externalExtra` 里的 secret 类字段用 `ExternalApiKeyCipher` 加密存储（或整段加密），与 `externalApiKeyEnc` 同口径；读取时解密后再走现有脱敏。

### P1-4 身份头兜底 `X-User-ID` 仍有两处残留（当前不可达，但违反基线）

- **位置**：`web/auth/JwtInterceptor.java:74`、`config/PlatformAguiRuntimeContextResolver.java:163`
- **证据**：

```java
// JwtInterceptor.java:69-78
public static String resolveUserId(HttpServletRequest request) {
    Object attr = request.getAttribute(ATTR_USER_ID);
    if (attr instanceof String userId && !userId.isBlank()) { return userId; }
    String header = request.getHeader(USER_ID_HEADER);            // ← X-User-ID 兜底
    if (header != null && !header.isBlank()) { return header.trim(); }
```

```java
// PlatformAguiRuntimeContextResolver.java:162-166 —— AG-UI 入口同样有兜底
// 2. X-User-ID（显式开发身份）
String header = request.firstHeader(JwtInterceptor.USER_ID_HEADER);
if (header != null && !header.isBlank()) { return header.trim(); }
```

- **可达性核验**：`WebConfig.java:43` 的 `setUrlPatterns(List.of("/api/*", "/agui/*"))` 使 `JwtAuthFilter` 覆盖两个入口的全部路径，且 filter 在 `HIGHEST_PRECEDENCE` 执行——**未带 token 的请求在 filter 层就被 401，走不到这两个兜底分支**。故当前**不可达**。
- **为什么仍要报**：第一轮声明的目标是「身份只认 JWT」，`KnowledgeBaseController` / `GroupController` 的同类兜底已删，这两处是**漏网**。它们构成 fail-open 残留——一旦有人调整 filter 的 `urlPatterns`（例如新增一个不带 `/api` 前缀的入口，或把某个路径加进 `PUBLIC_PATHS`），兜底立即变成**任意伪造身份**的漏洞。
- **建议修法**：删除两处 header 分支；顺带清理 `JwtInterceptor.USER_ID_HEADER` 常量与 `AgentManagementController:166` 等注释里的「（JWT / X-User-ID）」表述，避免误导后来者。

### P1-5 `SessionWorkspaceManager` 先执行删除、后做路径段校验（顺序颠倒）

- **位置**：`service/SessionWorkspaceManager.java:150` 与 `:155`、`:171-174`
- **证据**：

```java
// SessionWorkspaceManager.java:150-155 —— 150 行直接 resolve，未清洗
Path harnessSession = resolveRuntimeRoot(agentId).resolve(".harness").resolve(sessionId);
if (Files.exists(harnessSession)) {
    deleteRecursively(harnessSession);                                  // ← 危险动作先发生
    log.info("Deleted session harness workspace: {}", harnessSession);
}
Path dir = resolveSessionDir(agentId, sessionId);                       // ← 155 行才走 safeResolve 校验
```

```java
// SessionWorkspaceManager.java:171-174 —— 子会话 id 同样直接 resolve
for (String subSessionId : lineageService.subSessionsOf(userId, sessionId)) {
    Path dir = resolveRuntimeRoot(agentId).resolve(subSessionId);
```

- **对比**：同文件 `resolveSessionDir`（`:107-108` → `:223-230`）与 `resolveRuntimeRoot`（`:80-85`）都正确走 `safeResolve` + `startsWith` 校验。**只有这两处漏了**，且第一处**在校验之前**就完成了删除。
- **可利用率核验（如实说明）**：`sessionId` 由平台/框架生成，且会话域端点先过 `SessionAccessGuard.requireSessionOwned`（`:78-87`）查 `agent_session_owner` 表——非法 sessionId 查不到记录即 404；而非法 sessionId 也无法被创建出来（`PlatformAguiRuntimeContextResolver` 建目录时走 `resolveSessionDir` → 被 `sanitizeSegment` 拒绝）。所以**普通用户不可利用**。唯一残余路径是 **admin**（`owner.isEmpty() && isAdmin` → 放行）传入含 `..` 的 sessionId。因此我**没有**把它定为 P0。
- **为什么仍要修**：这是典型的纵深防御缺陷——「删除动作发生在校验之前」，只要将来有任何一条入口能透传 sessionId，就立刻升级为任意目录删除。修法成本极低。
- **建议修法**：150 行用 `safeResolve(resolveRuntimeRoot(agentId).resolve(".harness"), sessionId)`；172 行的 `subSessionId` 同样改走 `sanitizeSegment`；两者均可在删除前加 `path.startsWith(resolveRuntimeRoot(agentId))` 断言。

### P1-6 技能仓库快照 `Files.copy` 跟随符号链接，可把宿主任意文件内容读进 `resources`

- **位置**：`service/SkillRepositoryService.java:333-348`（复制）、`:350-378`（收集）
- **证据**：

```java
// SkillRepositoryService.java:333-348
private void copyRecursively(Path source, Path target) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.copy(file, target.resolve(source.relativize(file)),   // ← Files.copy 默认跟随符号链接
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
```

- **修正一条子审查的错误机制**：子审查称 `collectTextResources`（`:354,370`）会读符号链接目标、且符号链接环会无限遍历。**经核对这两条都不成立**——`Files.walk` + `filter(Files::isRegularFile)` 默认**不跟随**链接（`isRegularFile` 对符号链接返回 false），故收集阶段安全、也不会成环。**但 `copyRecursively` 里 `Files.copy` 默认 `follow links`**，会把链接目标的内容复制成快照里的真实文件；此后 `collectTextResources` 在快照上读到的是普通文件，于是宿主机密（如 `references/x -> /etc/passwd`）就进了 `resources` JSONB，模型可直接读取。
- **触发前提**：技能仓库内容不可信（git / clawhub / skillhub 源）。谁配置仓库谁就能投毒——属**仓库维护者到宿主机**的越权，不是任意外部攻击者。
- **建议修法**：`visitFile` 内对 `Files.isSymbolicLink(file)` 直接 `CONTINUE` 跳过；或用 `Files.copy(..., LinkOption.NOFOLLOW_LINKS)`。

### P1-7 `ExternalApiKeyCipher` 缺密钥时 fail-open 到硬编码开发密钥

- **位置**：`service/ExternalApiKeyCipher.java:32-51`
- **证据**：

```java
// ExternalApiKeyCipher.java:34-39
if (keyB64 == null || keyB64.isBlank()) {
    log.warn("AGENT_PLATFORM_EXTERNAL_KEY 未配置，使用固定开发密钥（仅限本地验证，禁止生产使用）");
    this.key = devKey();                       // SHA-256("agent-platform-dev-external-key")
}
```

- **影响**：生产漏配 `AGENT_PLATFORM_EXTERNAL_KEY` 时，渠道凭据与外部 RAG key 用**全网可复现**的固定密钥「加密」，等同明文；拖库即可还原。仅有一条 WARN 日志，无启动失败。
- **对照**：`JwtService` 对空 secret 会在构造期抛 `WeakKeyException` 使应用**起不来**（fail-closed）。同一项目两套标准。
- **为什么报 P1 而不是 P2**：注释已声明「仅供本地验证」，说明作者知道风险——但**没有任何机制阻止生产误用**。「漏配环境变量」是最常见的上线事故形态。
- **建议修法**：改为 `@Profile("dev")` 条件注入，或加 `AGENT_PLATFORM_EXTERNAL_KEY_ALLOW_DEV_FALLBACK=true` 显式开关，默认 fail-closed。**注意兼容性**：若已有环境在跑且漏配，改动后需先补配密钥再上线，否则启动失败。

- ✅ **已修（`d0537f1`，2026-09-17 13:55）**：回退路径**整体删除**（比「加开关」更彻底——开关本身就是一条可能被误开的旁路）。构造函数改为校验后 `loadKey()`：未配置 / 非 base64 / 解码后非 32 字节 → 抛 `IllegalStateException`，应用启动失败。`devKey()` 与对 SHA-256 的依赖已删（常量池核对：`agent-platform-dev-external-key` 出现 **0** 次，`MessageDigest`/`devKey` 符号均已不存在）。附带两处收紧见 §九 已修表。`application.yml` 新增 `agent-platform.external.key`（无默认值）。新增 `ExternalApiKeyCipherTest`（13 例）。

---

## 四、P2

| # | 问题 | 位置 | 要点 |
|---|---|---|---|
| P2-1 | 大转录文件仍整文件读，OOM 风险未根除 | `AgentRuntimeService.java:573` | >8MB 只跳过**缓存**，`Files.readAllLines(file)` 仍全量进内存；5s 轮询下长会话持续触发 Full GC。建议流式逐行 + 行数上限 |
| P2-2 | 管理端「装配」对文件型 Agent 误报 404 | `AgentConfigService.java:184-186,226` | `assemble(UUID)` 用仅查库的 `requireConfig(id)`，而同文件 `get()` 已走 `requireConfigEntity`（含文件兜底）→ 读写路径不一致。改一行即可 |
| P2-3 | 看护类 Map 无界、以 sessionId 为键永不清 | `TaskDeliveryWatchdog.java:58`、`RunSilenceDetector.java:66,69`、`AguiWakeupTarget.java:78`、`InboxWatchdogWakeupService.java:60` | `lastSweepAt` / `lastAlertAt` / `staleGateRecoveryAttempted` 等长周期缓慢增长。建议加 TTL 或按 owner 表存活校验 |
| P2-4 | `PendingFormInterruptStore` 无界且无 TTL | `PendingFormInterruptStore.java:33,89` | 仅 run 成功路径 `clear`；被遗弃的会话条目常驻（每条含完整 `toolInput`）。建议 TTL 淘汰 |
| P2-5 | `AguiEventPersistenceService` 两个缓存不淘汰 | `AguiEventPersistenceService.java:73,76` | `sequences` / `ownerCache` 随会话数线性增长 |
| P2-6 | 正常 run 结束不释放租约 | `SessionActivityMiddleware.java:70-75` | `doFinally` 只调 `eventClock.end` + `tracker.end`，未删租约；靠 `RunLeaseReconciler` 判空闲 >2min 才回收。窗口内重启会向已完成会话推送「restart recovery」误报 |
| P2-7 | `RunSilenceDetector` 恢复存在 TOCTOU | `RunSilenceDetector.java:95,149-186` | T0 判 `isRunning` → T1 `forceEnd` 无条件 remove 整个条目；两极窄窗口内新起的 run 计数/时钟被清 → 409 互斥短暂失效 |
| P2-8 | 首个 push 失败即中断剩余投递 | `TaskDeliveryWatchdog.java:138-172` | `Flux.concat` 短路，且 `lastSweepAt` 已写入（冷却被消耗），该会话其余完成事件本轮丢失。建议每条包 `onErrorResume` |
| P2-9 | `title` / `transcript` 端点缺显式归属守卫 | `AgentManagementController.java:172-176,182-189` | 同组 archive/clear/recover 都有 `requireSessionOwned`，这两个只有 `requireUserId`。**当前不可越权**（下游 `SessionOwnerService.setManualTitle:135-136` 把 userId 放进 SQL WHERE、`transcript` 按 `(userId, sessionId)` 取 store key），但与同组防御不一致 |
| P2-10 | SSE 无单用户连接上限 | `NotifyStreamController.java:36-43` | `timeout=0` 永不过期，无并发连接数限制 → 资源耗尽型 DoS |
| P2-11 | 启动期三个重型 sweep 同步执行 | `SubagentSessionMaintenance.java:91-94`、`WakeupDispatcherBootstrap.java:40`、`StartupStaleTaskCleaner.java:63` | `@EventListener(ApplicationReadyEvent)` 串行阻塞就绪线程，且期间 WakeupDispatcher 尚未 start |
| P2-12 | `HitlRecoveryGuardMiddleware` 每条输入打 INFO | `HitlRecoveryGuardMiddleware.java:82-86` | 未区分是否真注入，高吞吐下日志喷涌。建议仅 `pending==true` 时 WARN |
| P2-13 | `UiTools.isGateShaped` 关键词不全 | `UiTools.java:58-62` | 缺「通过」「同意」「confirm」「confirmed」等；模型用 `ui_card` 输出「已通过」不判为闸门形 → 软护栏仍可绕过 |
| P2-14 | 实体无 `@Version` 乐观锁 | `domain/BaseEntity.java`、`AgentConfig`、`Skill`、`Tool` | 并发编辑 last-write-wins，可能静默丢更新 |

另有两条仅记录、暂不建议动：
- `AgentAssemblyService` 分段锁 map 随 `evict` 清理（量级 = 存活 Agent 数，非泄漏）；`evictAll` 与 `assemble` 并发时同 id 可能短暂生成两个锁对象 → 重复装配一次，最终缓存一致。
- `ExternalRagClient.java:111-113` 用 `escapeJson` 拼 URL 路径段（不转义 `/` `?`）；host 已受 `OutboundUrlGuard` 约束，危害有限，但应改用 `URLEncoder.encode`。

---

## 五、误报更正（回源核对后剔除）

子审查提出的以下 3 条经我读原始代码核对**不成立**，特此更正，避免误导后续修复：

### 纠正 1：`SessionExtensionService` 的 `get`/`deleteEntry`/`deleteAll` 不是 IDOR

子审查称这三个方法缺 `userId` 形参、可越权读删他人会话扩展（含教材正文），并自标「未证实」。核验结果为**不成立**：归属校验在 controller 层，三个端点**都已接入** `SessionAccessGuard`：

```java
// web/SessionExtensionController.java:62-64（get）
String userId = requireUserId(httpRequest);
accessGuard.requireSessionOwned(agentId, userId, sessionId, httpRequest);
return service.get(agentId.toString(), sessionId);
// :103-105（deleteEntry）、:117-121（deleteAll）同构
```

service 层无 `userId` 形参是**设计如此**（判定收敛在 `SessionAccessGuard` 一处，见其类注释第 26 行「产物视图与子任务汇报视图共用本判定，避免两处规则漂移」），不是缺口。

### 纠正 2：harness「框架 map 的 key 是不是 sessionId」的疑问不成立

子审查把这条列为 P1 并自标「未证实」，担心若框架用线程名作 key，`AguiWakeupTarget` 会误判在途闸门为陈旧、`ResumeCoordinatorDeadlockCleaner` 会误删全部活跃锁。核验框架源码**不成立**：

```java
// /tmp/agui-src/io/agentscope/core/agui/processor/AguiResumeCoordinator.java:47-48
private final ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> pendingInterruptsByThread;
private final ConcurrentMap<String, String> activeRunsByThread;
// :58 / :119 / :151 / :188 —— 全部以 input.getThreadId() 为 key
Map<String, AguiEvent.Interrupt> pending = pendingInterruptsByThread.get(input.getThreadId());
String previousRunId = activeRunsByThread.putIfAbsent(input.getThreadId(), input.getRunId());
```

变量名 `...ByThread` 指的是 **AG-UI 的 threadId**，而平台契约是 `threadId = sessionId`（AG-UI 入口即按此设计）。两处消费代码的前提成立，**不需要修**。

### 纠正 3：技能仓库「符号链接环导致无限遍历」与「收集阶段读链接目标」不成立

见 P1-6：`Files.walk` 与 `filter(Files::isRegularFile)` 默认不跟随链接，收集阶段安全、也不会成环。真实缺口只在 `copyRecursively` 的 `Files.copy` 默认跟随链接上（P1-6 已按修正后的机制记录）。

---

## 六、第一轮修复核验（全部确认落地）

| 修复项 | 结论 | 关键证据 |
|---|---|---|
| JWT 密钥 fail-fast | ✅ | `JwtService` 构造期抛 `WeakKeyException`（空 secret 起不来） |
| 公开注册强制 END_USER | ✅ | `UserController.java:43` 注释明确不接受请求体 role |
| 会话域接口接 `SessionAccessGuard` | ✅ | clear/archive/recover/progress 全走 `requireSessionOwned`（`AgentManagementController:204,275,290,304`）；扩展接口 3 处亦已接入 |
| SSRF 出网守卫 | ✅ | `OutboundUrlGuard.requireSafe` 拦 loopback/链路本地/私有网段；`ExternalRagClient:59`、`AlertChannelDispatcher:367` 均已过守卫；HttpClient 不跟随重定向 |
| 渠道凭据 AES-GCM | ✅ | `ExternalApiKeyCipher` 随机 IV（`:58-59`）、TAG 128、无明文回退；`ChannelService.toRecord` 不回显 |
| 闸门护栏从 `ui_form` 移到 `ui_card` | ✅ | `UiTools.java:122` `isGateShaped` 守卫已归位；`formTool` 错位守卫已清理（`:80-89`）；四工具 name 常量与实现一一对应（`:33-37`） |
| `SessionActivityTracker` 计数原子化 | ✅ | `compute` + `computeIfPresent`（`:29-45`） |
| `SessionEventClock` 引用计数 + run 起跑登记 | ✅ | `:35-71`；`SessionActivityMiddleware:61` 起跑即 `begin` |
| `AguiWakeupTarget` defer / `filterLive` 对账 / try-catch | ✅ | `:160,290-296,337-338` |
| `WakeupInterruptRegistrar` `compute` + 空检查 | ✅ | `:190-197`（compute）、`:153`（toolUse 判空）；`buildInterrupt` 已加空检查 |
| `TaskDeliveryWatchdog` 先落库再唤醒 | ✅ | `:138-172` `Flux.concat(pushes).then(enqueueWakeup)` |
| `existsBySessionId` 替代 `findAll().anyMatch` | ✅ | `SessionProgressService:545-549` |
| `StartupStaleTaskCleaner` 父目录名判定 | ✅ | `:95-101` |
| 装配分段锁 + 双检、evict 清两级缓存 | ✅ | `AgentAssemblyService:381-396,1129-1152` |
| 转录缓存有界 LRU(16) + 加锁 | ✅ | `AgentRuntimeService:415-427,519-536` |
| clawhub 分页三重终止 + `cacheLock` | ✅ | `SkillRepositoryService:735,760,189` |
| 事件队列 `AtomicInteger` 阈值 | ✅ | `AguiEventPersistenceService:69,116`；`queueLimit=20000` |
| 知识库专用摄入线程池 | ✅ | `KnowledgeBaseService:453-458` |
| 密钥出参脱敏 + 保存回填 | ✅ | `:639-640`（脱敏）、`:665-670`（回填） |
| embedding 失败抛异常（可开关） | ✅ | `EmbeddingClient:54,74-77,111`，默认禁 hash 回退 |
| 验证码限流表有界 | ✅ | `UserService:174-185` `MAX_SEND_THROTTLE_ENTRIES=10000` + 淘汰 |
| 移除事务跨远程调用 | ✅ | `KnowledgeIngestionService:54`、`UserService:62`、`AuthService:99,263` 均已无 `@Transactional` |
| 告警未读抑制加时效 | ✅ | `AlertNotifier:208-212`、`PlatformNotificationRepository:64`、`AlertProperties:30`（默认 24h） |
| 渠道扇出专用池 + `awaitFanOutIdle` | ✅ | `AlertNotifier:94-95,103-117`（原子计数） |
| 渠道发送重试有界（仅 5xx/超时）+ 超时 | ✅ | `AlertChannelDispatcher:85,100-103,195-198,277` |
| 钉钉/飞书签名算法 | ✅ | `:443-446`（HmacSHA256 key=secret, data="ts\nsecret"）、`:449-452`（key="ts\nsecret", data=""） |
| `@Modifying` + `@Transactional` | ✅ | `AgentSessionOwnerRepository:38-84` 方法级逐个标注 |
| `toRealPath()` 防符号链接逃逸 | ✅ | `SessionArtifactController:200-211` |
| 异常处理器补全 | ✅ | `GlobalExceptionHandler:56-75,140-144`，无堆栈泄露 |
| `scheduling.pool.size=4` | ✅ | `application.yml:32` |
| V45/V46 与实体一致、无残留引用 | ✅ | 全仓 grep 无 `subagentIds` / `subagentTemplates` |
| 前端身份头兜底移除 | ✅ | 产物中 `X-User-Role` 出现 0 次 |

---

## 七、修复优先级建议

1. **P0-1**（OAuth 身份合并）—— 先确认 `userinfoUrl` 的部署实况，未配则必须立刻修。
2. **P1-1**（GC 删 `.harness`）—— 影响面是整个 Agent，且是"开了功能就中招"，修法 3 行。
3. **P1-2**（`subagents[].prompt` 泄露）—— 广场接口对所有登录用户开放，是实际在线的泄露面。
4. **P1-4 / P1-5**（身份头残留 / 删除顺序）—— 纵深防御，改动小、消除"将来变 P0"的隐患。
5. **P1-3**（KB 密钥明文落库）—— 需改存储格式，注意存量数据兼容与迁移。
6. ~~**P1-6**（技能快照符号链接）~~ —— 前提较窄，但修法明确，仍未动。
7. P2 按影响排期；P2-2（一行改动）、P2-9（补齐守卫）可顺手带上。

> P1-7 原列在此（「加密 fail-open」），已于 `d0537f1` 修复，不再属于待办。

---

## 八、审查边界与未证实项

- **未证实**：DB 侧 CHECK 约束与 `(session_id, event_sequence)` 唯一约束的实际存在性（未连库核对，仅读迁移脚本）；`external_extra` 中各 provider 实际写入的敏感字段名。~~OAuth `userinfoUrl` 的部署配置值~~ → **13:20 已核实**（`application.yml:217` 有非空默认值）。
- **超出本次范围**：限流是否在前置网关补齐。~~生产 `AGENT_PLATFORM_EXTERNAL_KEY` 的配置实况~~ → **13:20 已核实**（仓库无配置 + 运行日志实证 dev 密钥曾启用）。
- 所有结论基于 `c049893` 静态阅读 + 框架源码 `/tmp/agui-src` 交叉验证，未做运行时复现（除上述配置实况取的是工作区运行日志）。

---

## 九、修复进度（2026-09-17 13:55 更新）

### 已修（6 项，`mvn -o test` 243 项全绿）

| 项 | 修复内容 | commit |
|---|---|---|
| **P1-7** | `ExternalApiKeyCipher` **删除 fail-open 回退**：未配置 / 非 base64 / 解码后非 32 字节一律抛 `IllegalStateException`（启动失败）；删 `devKey()`。附带收紧两处：① 强制 32 字节（`new SecretKeySpec(非32字节,"AES")` 构造期不报错，要到首次加解密才炸——最常见的踩坑是误填 64 位十六进制字符串，它是合法 base64、解码成 48 字节，现在启动期就报错并给出长度诊断）；② 容忍环境变量首尾空白（k8s Secret / `docker --env-file` 易带入），不容忍值内部空白。`application.yml` 新增 `agent-platform.external.key`（无默认值，按 `auth.jwt-secret-key` 范式写明「未配置即启动失败」）。新增 `ExternalApiKeyCipherTest`（13 例）。核对产物：`javap -v` 常量池内 `agent-platform-dev-external-key` **0** 次、无 `MessageDigest`/`devKey` 符号 | `d0537f1` |
| **P0-1** | OAuth `userinfo-url` 未配置时不再伪造 `user_id="oauth-user"`，改 **fail-fast**：`AuthProperties` 新增 `@PostConstruct validateOauthConfig()`（**仅在 OAuth 已启用**——`authServerUrl`+`clientId` 均非空——时要求非空，否则启动失败并给出 `AGENT_PLATFORM_OAUTH_USERINFO_URL` 指引）+ `AuthService.fetchUserinfo` 改抛 `ApiException(500)` 作运行期纵深兜底；新增 `AuthPropertiesTest`（5 例，含「OAuth 未启用 / 半配置时留空仍须放行」这条边界）。已用 `javap` 核对字节码为 `athrow`、产物内 `oauth-user` 出现 0 次 | `aa26602` |
| P1-1 | `RuntimeWorkspaceGc` 只回收非点开头目录，不再误删 `.harness` 共享目录（新增 `isReclaimableSessionDir`） | `a103f67` |
| P1-2 | `AgentConfigResponse.redacted()` 连带摘掉 `subagents[].prompt`（新增 `SubagentInline.redacted()`） | `af8a911` |
| P1-4 | 删除 `X-User-ID` 兜底（`JwtInterceptor` + AG-UI 入口）、清掉 `USER_ID_HEADER` 常量与三处过时注释；测试由 `resolve_usesXUserHeader_whenNoJwt` 反转为 `resolve_ignoresXUserHeader_whenNoJwt`（断言 401） | `e837ac5` |
| P1-5 | `.harness` 会话副本改走 `safeResolve`（非法 id 直接 400）；子会话 id 来自框架 spawn registry，非法只跳过 + WARN | `974d80b` |

### 待处理（各有前置条件，不宜与上面混批）

| 项 | 前置条件 |
|---|---|
| P1-3 | 涉及存储格式变更，需连带设计存量数据的兼容/迁移方案 |
| P1-6 | 修法明确（`visitFile` 跳过符号链接），前提是技能仓库被当作不可信输入 |
| ~~P1-7~~ | ✅ 已修 `d0537f1` —— 原判定（是否真在跑）已取证、迁移顺序已核，见下方上线注意 |
| P2 × 14 | 按影响排期；其中 P2-2（改一行）、P2-9（补齐 `requireSessionOwned` 守卫）可顺手带上 |

> **⚠️ P1-7 上线注意（改动是破坏性的，必读）**：修复后**未配 `AGENT_PLATFORM_EXTERNAL_KEY` 的环境无法启动**（含本地开发）。两个既定事实决定了过渡必须分步：
> - **dev 密钥确曾在跑**：全仓库（yml / sh / Dockerfile / .env）**无任何地方**配置该变量，且工作区留有运行期实证 —— `workspace/runtimes/.../uploads/76c13d8f-…-logs.txt:52`：`2026-09-05T19:27:01 WARN c.t.a.service.ExternalApiKeyCipher : AGENT_PLATFORM_EXTERNAL_KEY 未配置，使用固定开发密钥`。即现存密文是用 dev 密钥加密的，**直接配一个真随机钥 = 换钥 = 存量全部解不开**。
> - **影响面比类名大**：该 cipher 不只保护 KB 的 `externalApiKey`，还加密 `channel_credentials.cred_value`（`ChannelService:208` 对凭据 map 的**每个 value** 加密，含 webhook 地址 / 加签 secret / Bearer token；`AlertChannelDispatcher:433` 解密）。
>
> **过渡顺序**：① 先令 `AGENT_PLATFORM_EXTERNAL_KEY = base64(sha256("agent-platform-dev-external-key"))` = `10RTMGKmgsf+D2QkJwYtq9Vmwx9QZKVDxQu0EUlsNck=`（已用真实类实测与旧 `devKey()` 等价，存量可解）→ ② 逐条重存凭据（渠道保存即整体覆盖写自动转新钥；KB 须**重新输入** API Key，因为 `KnowledgeBaseService` 仅在显式传入密钥时才重加密）→ ③ 再换 `openssl rand -base64 32` 的真随机钥。
>
> ⚠️ 轮换期间渠道侧失败是**静默**的：`AlertChannelDispatcher#decodeCredential` 把解密失败判定为「存量明文」原样返回且只 `log.debug` → 会拿密文当 secret 去加签 → 投递失败无显式报错（KB 侧相反，`ExternalRagClient` 未包 try/catch，抛 500 可见）。

### 待处理项的改动面（文件清单，2026-09-17 13:20 核）

> 行号按当前 HEAD（`5acc464`）复核，与正文中的行号一致。**原四项中只有 `SkillRepositoryService` 有测试文件**；`AuthService` / `KnowledgeBaseService` / `ExternalApiKeyCipher` 三个类原本**完全没有测试** —— 其中 `AuthPropertiesTest`（P0-1）、`ExternalApiKeyCipherTest`（P1-7）已随修复补齐。

| 项 | 需要改的文件 | 关联文件（会被连带影响） |
|---|---|---|
| ~~**P0-1**~~ ✅ 已修 `aa26602` | ~~`service/AuthService.java`（`:393-397` 伪造分支在 `:396`；消费点 `syncOauthUser` `:411-422`；两个入口调用 `fetchUserinfo`：`:131`、`:270`）<br>`config/AuthProperties.java`（`:57 userinfoUrl = ""`；同文件 `validateJwtSecret()` 已有 `@PostConstruct` fail-fast 先例可复用）~~ → 实际改动：`AuthProperties` 新增 `validateOauthConfig()`（带 `isEnabled()` 条件）、`fetchUserinfo` 改为抛 500、新增 `AuthPropertiesTest` | ~~`application.yml:206-217`~~ （`:217` 的 `${VAR:非空默认值}` 使原分支不可达，未改动） |
| **P1-3** | `service/KnowledgeBaseService.java`（写入 `:155`/`:171` 建、`:231-233`/`:248-251` 改；脱敏与回填 `:639-640`/`:665-670`/`:714`）<br>`domain/KnowledgeBase.java`（`:38 embeddingModelConfig`、`:59 externalExtra`；`:55 externalApiKeyEnc` 是加密范式） | `service/ExternalRagClient.java`（`:53` 解密 apiKey、`:60` 读 `externalExtra` —— 改成密文存储后这里必须解密）<br>`db/migration/V22__knowledge_base_core.sql`（`:10`、`:15` 列定义，`:18` CHECK 约束） |
| **P1-6** | `service/SkillRepositoryService.java`（`:289-302 snapshotGitSkillDir`、`:333-348 copyRecursively`，`Files.copy` 在 `:343`） | ⚠️ **同文件第二处同样问题**：`:698 Files.readString(file)`（扫描源于 `:680` 的 `walkFileTree`）——仓库里 `SKILL.md` 若是符号链接，`readString` 会跟随并读宿主机文件，内容进 `card.markdown()`；信任模型与 P1-6 完全一致<br>`src/test/.../SkillRepositoryServiceTest.java`（现有用例全为解析/目录扫描，**无符号链接用例**） |
| ~~**P1-7**~~ ✅ 已修 `d0537f1` | ~~`service/ExternalApiKeyCipher.java`（`:33-38` 构造器，`:34-36` 即 fail-open 分支；`devKey()` 在 `:43`）~~ → 实际改动：构造器 + 新增 `loadKey()`（校验空白/base64/32 字节）、删 `devKey()`、类 javadoc 补「实际服务 KB Key **与**渠道凭据」及轮换注意事项 | ~~依赖方（行为随开关变化）：`ChannelService` / `AlertChannelDispatcher` / `KnowledgeBaseService` / `ExternalRagClient`~~（构造函数签名不变，无需改动）<br>`application.yml` → 已新增 `agent-platform.external.key`（`:200-207`）<br>新增 `ExternalApiKeyCipherTest`（13 例）<br>`docs/knowledge-base-module-design.md:380` 同步（`da0b316`） |

### 顺带发现

**已清（`5acc464`）**：前端 `src/api/` 下 5 处注释仍在描述已不存在的 `X-User-ID` / `X-User-Role` 行为（`auth.ts:61`、`client.ts:164`、`client.ts:179`、`session.ts:109`、`platform.ts:5`）—— 纯注释，已改为描述「只用 JWT / 只发 `Authorization`」。核验：tsc + build 通过，新产物 `X-User-Role`/`X-User-ID` 各 **0** 次、`Authorization` 4 次。`platform.ts:385` 那段「后端已移除角色头兜底」是解释**为何不发**，属当前行为，保留。

**未改（新发现）**：
1. `frontend/src/api/client.ts:13 getUserId()` **全前端零调用**（唯一用途是喂已删除的 `X-User-ID` 头），已成死代码；连带 `user_id` 这个 localStorage 键只写不读（`auth.ts` 3 处写、`client.ts:105` 写、`:147` 登出删）。清理需一起动：函数 + 3 处写入 + 登出摘除。
2. 本地 `frontend/dist/`（gitignored、未跟踪）是移除身份头**之前**的旧产物，仍含 `X-User-Role`。仓库无影响，但若从本地 dist 直接部署需重新 build。
