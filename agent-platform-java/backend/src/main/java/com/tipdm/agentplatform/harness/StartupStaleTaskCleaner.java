package com.tipdm.agentplatform.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import io.agentscope.harness.agent.bus.MessageBus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动清理：把「重启后失去执行者的 in-flight 后台任务」标记为 FAILED，并唤醒受影响会话。
 *
 * <p>两类任务落盘都要处理：</p>
 * <ol>
 *   <li>旧布局 {@code runtimes/{agent}/{session}/agents/{agent}/tasks/{session}.json}
 *       （{@code WorkspaceTaskRepository}，2.0.2 时代）；</li>
 *   <li>2.0.3 的异步工具记录
 *       {@code runtimes/{agent}/.agentscope/bus/async-tools/{session}/call_*.json}
 *       （{@code WorkspaceAsyncToolRegistry}）。2026-09-11 实测：只扫旧布局会漏掉
 *       2.0.3 新会话的在途 {code agent_spawn}，导致父会话永远等不到完成通知。</li>
 * </ol>
 *
 * <p>仅标记 FAILED 还不够——空闲会话没有输入/通知就不会重新拉起。因此对每个受影响
 * 会话额外推送一条 inbox 提示并按会话归属 {@code enqueueWakeup}，复用子代理完成时的
 * 唤醒链路，让父会话自动恢复并按「任务丢失」收敛（重新调度或上报），而不是死等。</p>
 */
@Component
public class StartupStaleTaskCleaner {

    private static final Logger log = LoggerFactory.getLogger(StartupStaleTaskCleaner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MARK_REASON =
            "task interrupted: platform restarted while this in-flight task was running "
                    + "(no executor). This is NOT a failure of the subagent itself.";
    /** 产物清单最多列出的条目数（超出只报数量）。 */
    private static final int MAX_ARTIFACT_LISTING = 20;

    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionOwnerService sessionOwnerService;
    private final MessageBus messageBus;

    public StartupStaleTaskCleaner(
            SessionWorkspaceManager sessionWorkspaceManager,
            SessionOwnerService sessionOwnerService,
            MessageBus messageBus) {
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.sessionOwnerService = sessionOwnerService;
        this.messageBus = messageBus;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markStaleTasksFailed() {
        Path root = sessionWorkspaceManager.getRuntimesRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        // 两种布局都要收集"受影响会话"：只标记不通知会让会话重启后一直不动
        // （2026-09-11 实测：旧布局 tasks/*.json 丢失的 agent_send 被标记 FAILED，
        // 但无人唤醒父会话，会话静默等到 L3 看护的 15 分钟兜底）。
        Map<String, String> affectedSessionToAgent = new LinkedHashMap<>();
        Map<String, String> artifactListings = new LinkedHashMap<>();
        markLegacyTaskFiles(root, affectedSessionToAgent, artifactListings);
        markStaleAsyncTools(root, affectedSessionToAgent, artifactListings);

        if (affectedSessionToAgent.isEmpty()) {
            log.info("Startup: no stale running tasks found");
        }
        // 通知受影响会话：重置待任务失败 + 触发自动唤醒（复用子代理完成链路）
        for (Map.Entry<String, String> e : affectedSessionToAgent.entrySet()) {
            notifySessionTaskLost(e.getKey(), e.getValue(),
                    artifactListings.get(e.getKey()));
        }
    }

    /**
     * 旧布局：runtimes/{agent}/{session}/agents/{agent}/tasks/{session}.json。
     * 标记结果写入 affected（sessionId → agentId），盘上产物清单写入 artifactListings。
     */
    void markLegacyTaskFiles(Path root, Map<String, String> affected,
                             Map<String, String> artifactListings) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 6)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    // 用「直接父目录名 == tasks」判断布局，不用 contains("/tasks/")：
                    // 后者绑死 '/' 分隔符，Windows 下（\tasks\）永远匹配不上，
                    // 旧布局任务会一个都清不掉、也不会产生受影响会话通知。
                    .filter(p -> {
                        Path parent = p.getParent();
                        return parent != null && "tasks".equals(parent.getFileName().toString());
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("Legacy task sweep failed: {}", e.getMessage());
            return;
        }
        int marked = 0;
        for (Path f : files) {
            if (markIfRunning(f)) {
                marked++;
                // 路径前两段即 agentId / sessionId（与 async-tools 布局一致）
                Path rel = root.relativize(f);
                if (rel.getNameCount() >= 2) {
                    String agentId = rel.getName(0).toString();
                    String sessionId = rel.getName(1).toString();
                    affected.putIfAbsent(sessionId, agentId);
                    artifactListings.putIfAbsent(sessionId,
                            listSessionArtifacts(root.resolve(agentId).resolve(sessionId)));
                }
            }
        }
        if (marked > 0) {
            log.warn("Startup: marked {} legacy in-flight task(s) as CANCELLED "
                    + "(interrupted by restart, executor lost)", marked);
        }
    }

    /**
     * 2.0.3 异步工具布局：runtimes/{agent}/.agentscope/bus/async-tools/{session}/*.json。
     * 标记结果写入 affected（sessionId → agentId），盘上产物清单写入 artifactListings。
     */
    void markStaleAsyncTools(Path root, Map<String, String> affected,
                             Map<String, String> artifactListings) {
        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(".agentscope/bus/async-tools"))
                    .forEach(dir -> {
                        String agentId = root.relativize(dir).getNameCount() > 0
                                ? root.relativize(dir).getName(0).toString() : null;
                        try (Stream<Path> files = Files.walk(dir, 2)) {
                            files.filter(Files::isRegularFile)
                                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                                    .forEach(f -> {
                                        String sessionId = f.getParent() == null
                                                ? null : f.getParent().getFileName().toString();
                                        if (sessionId == null || sessionId.isBlank()
                                                || agentId == null || agentId.isBlank()) {
                                            return;
                                        }
                                        if (markIfRunning(f)) {
                                            log.warn("Startup: marked stale async tool record as "
                                                            + "CANCELLED (interrupted by restart): "
                                                            + "session={}, file={}",
                                                    sessionId, f.getFileName());
                                            affected.putIfAbsent(sessionId, agentId);
                                            artifactListings.putIfAbsent(sessionId,
                                                    listSessionArtifacts(
                                                            root.resolve(agentId).resolve(sessionId)));
                                        }
                                    });
                        } catch (IOException ex) {
                            log.debug("ignore async-tools subdir {}: {}", dir, ex.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Async-tool sweep failed: {}", e.getMessage());
        }
    }

    private boolean markIfRunning(Path file) {
        try {
            JsonNode node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            boolean[] changed = {false};
            fix(node, changed);
            if (changed[0]) {
                Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(node), StandardCharsets.UTF_8);
                return true;
            }
        } catch (IOException e) {
            log.debug("Stale task scan skip {}: {}", file, e.getMessage());
        }
        return false;
    }

    /**
     * 递归：仅处理带 taskId 且 status=RUNNING 的对象节点。
     *
     * <p>3.7 终态语义修正：RUNNING → {@code CANCELLED}（框架 {@code TaskStatus}
     * 里既有的可续跑终态），不再是 {@code FAILED}——子任务可能已产出全部产物
     * （2026-09-24 实训即此情形：5 件产物齐全却被标 FAILED），父会话收到通知后
     * 应先核对产物再决定续做/重派，而不是一律按失败处理。写自定义枚举字符串
     * 会让框架 {@code valueOf} 反序列化抛异常，故不新造状态名。</p>
     */
    private void fix(JsonNode node, boolean[] changed) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                fix(child, changed);
            }
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if ("RUNNING".equals(obj.path("status").asText())) {
                obj.put("status", "CANCELLED");
                if (!obj.has("lastError") || obj.path("lastError").asText().isEmpty()) {
                    obj.put("lastError", MARK_REASON);
                }
                changed[0] = true;
            }
            obj.fields().forEachRemaining(e -> fix(e.getValue(), changed));
        }
    }

    /**
     * 盘上产物清单（3.7）：会话 artifacts 目录的一级条目名。中断的子任务可能
     * 已经把产物写完，通知里带上清单，父会话不必盲目重派；目录不存在或为空
     * 返回「（盘上暂无产物）」。
     */
    private String listSessionArtifacts(Path sessionDir) {
        Path artifacts = sessionDir.resolve("artifacts");
        if (!Files.isDirectory(artifacts)) {
            return "（盘上暂无产物）";
        }
        try (Stream<Path> entries = Files.list(artifacts)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).sorted()
                    .limit(MAX_ARTIFACT_LISTING + 1L).toList();
            if (names.isEmpty()) {
                return "（盘上暂无产物）";
            }
            StringBuilder sb = new StringBuilder();
            List<String> shown = names.size() > MAX_ARTIFACT_LISTING
                    ? names.subList(0, MAX_ARTIFACT_LISTING) : names;
            sb.append(String.join("、", shown));
            if (names.size() > MAX_ARTIFACT_LISTING) {
                sb.append(" 等（仅列出前 ").append(MAX_ARTIFACT_LISTING).append(" 项）");
            }
            return sb.toString();
        } catch (IOException e) {
            log.debug("Artifact listing failed for {}: {}", artifacts, e.getMessage());
            return "（产物清单读取失败）";
        }
    }

    /**
     * 向受影响会话推送「后台任务因重启被中断」提示，并按会话归属触发 wakeup，
     * 使空闲父会话自动恢复。归属解析与 InboxWatchdogWakeupService 一致：
     * 父 agent 优先（给定 agentId 已知），owner 为空时由 wakeup 落库解析回填。
     *
     * <p>3.7：文案区分「被中断」与「子智能体自身失败」，并附盘上产物清单——
     * 提示父会话先核对产物（中断 ≠ 失败，产物可能已齐全）再决定续做或重派。</p>
     */
    void notifySessionTaskLost(String sessionId, String agentId, String artifactListing) {
        // 已归档（弃用）会话不唤醒、不推送：启动时按盘上残留任务记录唤醒会话，
        // 若不看归档，用户早已弃用/删掉的会话会在每次重启后被拉起来继续跑
        // （用户报障）。任务记录本身仍照常标记 CANCELLED——那是盘上事实的清理，
        // 与是否唤醒该会话是两件事。
        if (sessionOwnerService.isArchived(agentId, sessionId)) {
            log.info("Startup: session {} is archived (abandoned by user), "
                    + "marked stale task CANCELLED but skipped notification + wakeup",
                    sessionId);
            return;
        }
        String userId = sessionOwnerService.owner(agentId, sessionId).orElse("");
        String hintContent = "<system-notification>平台发生重启：本会话一个在途后台任务"
                + "（agent_spawn）的执行者随重启丢失，任务状态已标记为 CANCELLED"
                + "（被中断，不是子智能体自身失败）。请先核对盘上产物再决定下一步——"
                + "中断的任务可能已产出部分或全部产物，盘上现有产物："
                + (artifactListing == null ? "（盘上暂无产物）" : artifactListing)
                + "。若产物已齐全请直接登记收口；若缺失请仅重派缺失部分，"
                + "或按流程上报主编。</system-notification>";
        Map<String, Object> payload = Map.of(
                "type", "hint",
                "id", UUID.randomUUID().toString().replace("-", ""),
                "hint", hintContent,
                "source", "subagent_task");
        try {
            messageBus.inboxPush(sessionId, payload).subscribe();
            messageBus.enqueueWakeup(userId, sessionId, agentId).subscribe();
            log.warn("Startup: pushed task-lost notification + wakeup for session={} agent={} user={}",
                    sessionId, agentId, userId.isEmpty() ? "(blank)" : userId);
        } catch (RuntimeException e) {
            log.warn("Startup: failed to notify session {} about lost task: {}",
                    sessionId, e.getMessage());
        }
    }
}
