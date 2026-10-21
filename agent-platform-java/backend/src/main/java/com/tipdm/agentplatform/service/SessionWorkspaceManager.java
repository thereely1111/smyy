package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.AgentScopeWakeupConfig;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 运行态工作区管理器：会话产物目录（{@code runtimes/{agentId}/{sessionId}}）的
 * 创建、解析与回收，与开发态定义区（{@code agents/{agentId}/}）物理隔离。
 *
 * <p>布局目标：开发态定义区只承载 AGENTS.md / skills / subagents 等定义文件，
 * 模型运行产物一律落入运行态目录，由平台创建与清理，杜绝
 * 「开发者保存配置清空用户会话产物」与「会话目录跑偏仓库根」两类问题。</p>
 *
 * <p><strong>为什么是两层而不是三层（含 userId）：</strong>装配期
 * （{@code AgentAssemblyService}）只能拿到 agentId——AG-UI 注册是
 * {@code registerFactory(agentId, () -> assemble(config))}，闭包里没有 userId，
 * 而文件系统规格是装配期构造的：可写根只能到 agent 层，「按会话隔离」由框架按
 * {@code RuntimeContext.sessionId} 追加的命名空间实现。平台若另行创建三层目录，
 * 两套路径就会并存（三层空壳、两层才有产物）。</p>
 *
 * <p><strong>用户归属与隔离分别由谁负责：</strong>
 * <ul>
 *   <li>归属（知道是谁的）：逻辑层的 {@code agent_session_owner} 表记录
 *       (agent_id, session_id, user_id)，产物视图按此校验；
 *   <li>隔离（别人看不到）：装配期的 {@code IsolationScope.SESSION} 把模型视野
 *       收敛到自己的会话目录，与目录里有没有 userId 层无关。
 * </ul>
 * 目录只负责让「平台托管的目录」与「模型写入的目录」是同一个。</p>
 */
@Service
public class SessionWorkspaceManager {

    /** 仅用于解析 wakeup 队列条目（读 {@code sessionId} 字段），不参与任何写路径。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper WAKEUP_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(SessionWorkspaceManager.class);

    /** 运行态根目录名（相对 workspace-root） */
    public static final String RUNTIMES_DIR = "runtimes";

    private final Path workspaceRoot;

    /**
     * 血缘查询（框架 spawn registry）：用于删除会话时联动清理其子智能体目录。
     * 允许为 {@code null}（单测与不需要血缘的场景）：此时只删本会话目录。
     */
    private final SessionLineageService lineageService;

    /** 无血缘依赖的构造器（单测 / 纯目录操作：只删本会话目录）。 */
    public SessionWorkspaceManager(@Value("${agent-platform.workspace-root}") String workspaceRoot) {
        this(workspaceRoot, null);
    }

    /** 生产构造器：注入血缘服务，删除会话时联动清理其子智能体目录。 */
    @Autowired
    public SessionWorkspaceManager(@Value("${agent-platform.workspace-root}") String workspaceRoot,
                                   SessionLineageService lineageService) {
        this.workspaceRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        this.lineageService = lineageService;
    }

    /**
     * runtimes/{agentId} —— 单个智能体的运行根，
     * 装配时作为文件工具（execute / read_file / write_file）的 project 根，
     * 与定义区（agents/{agentId}/）分离后，模型产物无法覆盖定义文件。
     *
     * <p>也是 execute 的 shell cwd：模型写相对路径即落在自己的会话目录
     * （{@link #resolveSessionDir}），与平台托管的目录是同一个。</p>
     */
    public Path resolveRuntimeRoot(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw ApiException.badRequest("agentId 不能为空");
        }
        return safeResolve(workspaceRoot.resolve(RUNTIMES_DIR), agentId);
    }

    /**
     * runtimes/{agentId}/{sessionId} —— 会话产物目录（幂等创建）。
     *
     * <p>不带 userId 层：装配期拿不到 userId，平台托管的目录与模型写入的目录必须一致，
     * 用户归属改由 {@code agent_session_owner} 表承载，见类注释。</p>
     */
    public Path ensureSessionDir(String agentId, String sessionId) {
        Path dir = resolveSessionDir(agentId, sessionId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create session workspace: {}", dir, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "创建会话工作区失败: " + e.getMessage());
        }
        return dir;
    }

    /**
     * 解析会话产物目录（不创建）。
     */
    public Path resolveSessionDir(String agentId, String sessionId) {
        return safeResolve(resolveRuntimeRoot(agentId), sessionId);
    }

    /**
     * workspace-root 本身（供平台级共享总线等使用）。
     */
    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * runtimes/ 目录本身（供 GC 扫描时遍历）。
     */
    public Path getRuntimesRoot() {
        return workspaceRoot.resolve(RUNTIMES_DIR);
    }

    /**
     * 删除整个智能体的运行态根（runtimes/{agentId}，含其下全部用户会话产物），
     * 供删除智能体时联动清理；目录不存在时静默返回。
     */
    public void deleteRuntimeRoot(String agentId) {
        Path root = resolveRuntimeRoot(agentId);
        if (!Files.exists(root)) {
            return;
        }
        deleteRecursively(root);
        log.info("Deleted runtime root: {}", root);
    }

    /**
     * 删除会话产物目录（含其子智能体会话目录）。
     *
     * <p>按用户维度清理时由调用方结合 {@code agent_session_owner} 表
     * 先筛出该用户的 sessionId 列表，再逐个调用。</p>
     *
     * @param userId 会话归属用户：子会话血缘按 {@code userId:sessionId} 索引，缺失时跳过子目录联动清理
     */
    public void deleteSessionDir(String agentId, String userId, String sessionId) {
        // 共享总线上该会话的 inbox 队列（含未消费条目）联动清理，避免残留。
        deleteSessionInboxQueue(sessionId);
        // wakeup 队列条目同样要清：inbox 清了但 pending wakeup 留着，启动时
        // WakeupDispatcher 仍会 drain 它并拉起一个已删会话的 run。
        deleteSessionWakeupEntries(sessionId);
        // harness workspace 根（runtimes/{agentId}/.harness）下的会话本地副本联动清理。
        Path harnessSession = safeResolve(resolveRuntimeRoot(agentId).resolve(".harness"), sessionId);
        if (Files.exists(harnessSession)) {
            deleteRecursively(harnessSession);
            log.info("Deleted session harness workspace: {}", harnessSession);
        }
        Path dir = resolveSessionDir(agentId, sessionId);
        if (Files.exists(dir)) {
            deleteRecursively(dir);
            log.info("Deleted session workspace: {}", dir);
        }
        // 删除父会话后不留孤儿：子智能体会话目录与父会话目录同级，靠框架 spawn registry 定位。
        deleteSubagentDirs(agentId, userId, sessionId);
    }

    private void deleteSubagentDirs(String agentId, String userId, String sessionId) {
        if (lineageService == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        // 子智能体会话目录与父会话目录同级（runtimes/{agentId}/sub-*），目录名不携带父会话 ID。
        // 归属关系取自框架记录在父会话状态里的 spawn registry（SessionLineageService），
        // 因此这里不需要扫描任何文件内容。
        for (String subSessionId : lineageService.subSessionsOf(userId, sessionId)) {
            Path dir;
            try {
                // 子会话 id 取自框架 spawn registry，属内部数据；但它仍是「外部写入的字符串」，
                // 不能直接 resolve —— 一条脏数据（含 .. 或 /）就会越出 runtimes/{agentId}。
                // 清理路径上遇到脏数据只跳过并告警，不中断其余子目录的回收。
                dir = safeResolve(resolveRuntimeRoot(agentId), subSessionId);
            } catch (ApiException e) {
                log.warn("Skip illegal subagent session id {} for parent {}: {}",
                        subSessionId, sessionId, e.getMessage());
                continue;
            }
            if (Files.isDirectory(dir)) {
                deleteRecursively(dir);
                log.info("Deleted subagent workspace for session {}: {}", sessionId, dir);
            }
        }
    }

    /**
     * 删除某会话在共享总线上遗留的 inbox 队列：{workspaceRoot}/.agentscope/bus/queues/agentscope_inbox_&lt;sessionId&gt;_*。
     *
     * <p>inbox 目录随每次 run 创建、drain 后条目删除但目录保留；会话清理/删除若不同步
     * 清理，会留下未消费条目与空目录。这里按前缀匹配删除，目录不存在时静默返回。</p>
     */
    public void deleteSessionInboxQueue(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Path queues = workspaceRoot.resolve(AgentScopeWakeupConfig.SHARED_BUS_ROOT).resolve("queues");
        if (!Files.isDirectory(queues)) {
            return;
        }
        String prefix = "agentscope_inbox_" + sessionId + "_";
        try (Stream<Path> entries = Files.list(queues)) {
            for (Path dir : entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .toList()) {
                deleteRecursively(dir);
                log.info("Deleted session inbox queue: {}", dir);
            }
        } catch (IOException e) {
            log.warn("Failed to clean session inbox queues for {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 删除某会话在共享总线上遗留的 wakeup 队列条目（{@code agentscope_wakeups_*}）。
     *
     * <p><b>为什么必须单独清理</b>：{@link #deleteSessionInboxQueue} 只删 inbox 前缀，
     * 而共享总线上还有另一类队列 —— wakeup 队列
     * （{@code WakeupDispatcherBootstrap} 启动即 drain「含迁移过来的历史 wakeup」）。
     * 删会话不清它，已删会话的 pending wakeup 会在下次启动时被 drain 并拉起 run
     * （用户报障：删掉的会话重启后还在跑）。</p>
     *
     * <p><b>为什么必须按内容过滤而不是按目录名删</b>：wakeup 队列目录名是
     * {@code agentscope_wakeups_<hashKey(userId)>}，<b>按 userId 聚合、不带 sessionId</b>
     * —— 同一用户的多个会话共用一个目录。按名字匹配会误删同用户其他会话的唤醒请求。
     * 故逐个读条目内容（{@code {userId, sessionId, agentId}}）比对 sessionId 后才删；
     * 解析不了的条目一律保留（宁留勿误删，唤醒侧另有归属与归档双判据兜底）。</p>
     *
     * <p>删空后的目录保留（与 inbox 队列一致，总线自身依赖目录存在）。</p>
     */
    public void deleteSessionWakeupEntries(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Path queues = workspaceRoot.resolve(AgentScopeWakeupConfig.SHARED_BUS_ROOT).resolve("queues");
        if (!Files.isDirectory(queues)) {
            return;
        }
        String prefix = "agentscope_wakeups_";
        int removed = 0;
        try (Stream<Path> dirs = Files.list(queues)) {
            for (Path dir : dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .toList()) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        if (!wakeupEntryTargets(file, sessionId)) {
                            continue;
                        }
                        if (Files.deleteIfExists(file)) {
                            removed++;
                            log.info("Deleted stale wakeup entry for deleted session {}: {}",
                                    sessionId, file.getFileName());
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to clean wakeup queue {} for {}: {}",
                            dir, sessionId, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan wakeup queues for {}: {}", sessionId, e.getMessage());
        }
        if (removed > 0) {
            log.info("Deleted {} stale wakeup entr(ies) for deleted session {}", removed, sessionId);
        }
    }

    /** wakeup 条目是否属于该会话：读 JSON 取 {@code sessionId} 比对；读不出则保守返回 false。 */
    private boolean wakeupEntryTargets(Path file, String sessionId) {
        try (InputStream in = Files.newInputStream(file)) {
            Map<?, ?> payload = WAKEUP_MAPPER.readValue(in, Map.class);
            return sessionId.equals(payload.get("sessionId"));
        } catch (IOException | RuntimeException e) {
            // 元文件（.keep 等）或损坏条目：不删。唤醒侧对无归属/无内容的条目本就会跳过，
            // 宁留一条无害条目，不可误删同用户其他会话的唤醒请求。
            return false;
        }
    }

    /**
     * 归一化解析 Agent 定义区：workspacePath 为相对路径（agents/{id}）或绝对路径均可，
     * 统一基于 workspace-root 解析并做前缀校验（防路径穿越）。
     * 注意：此处是整个路径的解析（可含多级目录），与 {@link #safeResolve} 的单段限制不同。
     */
    public Path resolveAgentDefinitionDir(String workspacePath) {
        String resolved = workspacePath == null || workspacePath.isBlank()
                ? Paths.get("agents").resolve("_default").toString()
                : workspacePath;
        Path path = workspaceRoot.resolve(resolved).normalize();
        if (!path.startsWith(workspaceRoot)) {
            throw ApiException.badRequest("非法工作区路径: " + workspacePath);
        }
        return path;
    }

    private Path safeResolve(Path base, String segment) {
        String safe = sanitizeSegment(segment);
        Path path = base.resolve(safe).normalize();
        if (!path.startsWith(base)) {
            throw ApiException.badRequest("非法路径段: " + segment);
        }
        return path;
    }

    /**
     * 路径段清洗：拒绝空段、绝对路径与「..」穿越，仅保留普通目录名。
     */
    private String sanitizeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            throw ApiException.badRequest("路径段不能为空");
        }
        String trimmed = segment.trim().replace('\\', '/');
        if (trimmed.startsWith("/") || trimmed.contains("..")
                || trimmed.contains("/") || trimmed.isBlank()) {
            throw ApiException.badRequest("非法路径段: " + segment);
        }
        return trimmed;
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("Failed to delete directory {} (残留不影响运行): {}", dir, e.getMessage());
        }
    }
}
