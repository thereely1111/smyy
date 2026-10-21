package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 完成契约探针（3.13）：把「会话是否真完成」从文本猜测换成可核验的产出事实。
 *
 * <p><b>契约（智能体侧约定，见 AGENTS.md）</b>：多阶段任务全部完成时，智能体在
 * 会话工作区落件 {@code session-complete.json}——不只是声明完成，而是背书产出：
 * {@code deliverables} 逐项列出交付件路径与落件时指纹。</p>
 *
 * <pre>{@code
 * {
 *   "summary": "阶段四全部交付件完成",
 *   "deliverables": [
 *     {"path": "artifacts/教材/前言.md", "sha256": "…", "minBytes": 200}
 *   ],
 *   "leftovers": []
 * }
 * }</pre>
 *
 * <p><b>核验（平台侧，纯确定性）</b>：</p>
 * <ol>
 *   <li>标记存在性；</li>
 *   <li>逐交付件存在、非空（≥ {@code minBytes}，缺省 1）、sha256 与声明一致
 *       ——交付件被修改而未重新声明 ⇒ 指纹失配 ⇒ 契约自动失效，不存在陈旧的
 *       完成状态；</li>
 *   <li>调用方叠加「用户未喊停」等事实（本类只管文件面）。</li>
 * </ol>
 *
 * <p>三态结论：{@code VERIFIED}（真终态）/ {@code INVALID}（标记在但产出核不过，
 * {@link #failures()} 可直接拼进补醒提示词）/ {@code MISSING}。</p>
 *
 * <p><b>工作痕迹（workEvidence）</b>：会话工作区存在计划文件（{@code plan/} 或
 * {@code plans/} 下的 .md）或契约标记本身 ⇒ 该会话立过结构性工作清单，完成契约
 * 是预期产物。纯问答会话（无计划、无标记）不适用契约，交由调用方的文本兜底——
 * 这把语义猜测压缩到最小适用面。</p>
 *
 * <p>路径解析：{@code path} 优先按会话工作区相对路径解析，其次按绝对路径
 * （模型跨会话引用绝对路径是既有习惯）。接受绝对路径但落件清单应优先相对路径，
 * 便于会话迁移。</p>
 */
@Service
public class SessionCompletionContractService {

    private static final Logger log = LoggerFactory.getLogger(SessionCompletionContractService.class);

    /** 契约标记在工作区的默认文件名。 */
    public static final String DEFAULT_MARKER_FILE = "session-complete.json";

    /** 框架 plan 目录的两个惯例名（PlanModeManager 默认 plans，本平台会话历史用 plan）。 */
    private static final List<String> PLAN_DIRS = List.of("plan", "plans");

    public enum Status { VERIFIED, INVALID, MISSING }

    /**
     * @param status      三态结论
     * @param failures    核验失败明细（VERIFIED/MISSING 为空）；可原样拼进补醒提示词
     * @param workEvidence 会话是否立过结构性工作清单（计划文件或契约标记存在）
     */
    public record ContractCheck(Status status, List<String> failures, boolean workEvidence) {

        public boolean verified() {
            return status == Status.VERIFIED;
        }
    }

    private final SessionWorkspaceManager workspaceManager;
    private final ObjectMapper objectMapper;
    private final String markerFile;

    public SessionCompletionContractService(
            SessionWorkspaceManager workspaceManager,
            ObjectMapper objectMapper,
            @Value("${agent-platform.completion-contract.file-name:" + DEFAULT_MARKER_FILE + "}")
            String markerFile) {
        this.workspaceManager = workspaceManager;
        this.objectMapper = objectMapper;
        this.markerFile = markerFile == null || markerFile.isBlank()
                ? DEFAULT_MARKER_FILE : markerFile;
    }

    /** 契约标记路径（会话工作区内），供智能体侧文档与排障对齐。 */
    public Path markerPath(String agentId, String sessionId) {
        return workspaceManager.resolveSessionDir(agentId, sessionId).resolve(markerFile);
    }

    /**
     * 完成契约是否已被后续工作「越过」。
     *
     * <p>契约声明的是某一阶段的交付集，而长流水线会话在契约通过后仍要继续做后续
     * 阶段；若闩锁永久有效，这些会话会永久失去自愈能力。判据取工作痕迹：契约标记
     * 落盘之后，子任务台账（{@code agents/{agentId}/tasks/{sessionId}.json} 或
     * async-tools 目录）若又被写过，说明契约并非会话终态。取 60s 宽限避开写序抖动；
     * 取不到时刻一律返回 {@code false}（维持闩锁有效），不做超出证据的推断。</p>
     */
    public boolean supersededByNewWork(String agentId, String sessionId) {
        try {
            Path root = workspaceManager.resolveSessionDir(agentId, sessionId);
            Path marker = root.resolve(markerFile);
            if (!Files.isRegularFile(marker)) {
                return false;
            }
            Instant landedAt = lastModified(marker);
            if (landedAt == null) {
                return false;
            }
            Instant newestDispatch = newestTaskLedgerMtime(root, agentId, sessionId);
            return newestDispatch != null
                    && newestDispatch.isAfter(landedAt.plusSeconds(DISPATCH_GRACE_SECONDS));
        } catch (RuntimeException e) {
            log.debug("supersededByNewWork check failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /** 契约落地与「新派发」之间的最小时间差，避开同一时刻写序抖动。 */
    private static final long DISPATCH_GRACE_SECONDS = 60;

    /**
     * 子任务台账的最后修改时刻。覆盖框架的两种布局：
     * {@code agents/{agentId}/tasks/{sessionId}.json}（单文件）与
     * {@code .agentscope/bus/async-tools/{sessionId}/}（目录）。
     */
    private Instant newestTaskLedgerMtime(Path root, String agentId, String sessionId) {
        Instant newest = lastModified(
                root.resolve("agents").resolve(agentId).resolve("tasks").resolve(sessionId + ".json"));
        Instant asyncTools = lastModified(
                root.resolve(".agentscope").resolve("bus").resolve("async-tools").resolve(sessionId));
        if (asyncTools != null && (newest == null || asyncTools.isAfter(newest))) {
            newest = asyncTools;
        }
        return newest;
    }

    private static Instant lastModified(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            return Files.getLastModifiedTime(path).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 核验会话完成契约。文件系统/解析异常一律降级为 {@code MISSING + workEvidence=false}
     * ——探针是看护的输入，绝不能因自身异常炸掉看护扫描。
     */
    public ContractCheck check(String agentId, String sessionId) {
        try {
            Path root = workspaceManager.resolveSessionDir(agentId, sessionId);
            Path marker = root.resolve(markerFile);
            boolean workEvidence = Files.isRegularFile(marker) || planFileExists(root);
            if (!workEvidence) {
                return new ContractCheck(Status.MISSING, List.of(), false);
            }
            if (!Files.isRegularFile(marker)) {
                return new ContractCheck(Status.MISSING, List.of(), true);
            }
            return verify(root, marker, workEvidence);
        } catch (Exception e) {
            log.debug("Completion contract check failed for session {}: {}", sessionId, e.getMessage());
            return new ContractCheck(Status.MISSING, List.of(), false);
        }
    }

    private ContractCheck verify(Path root, Path marker, boolean workEvidence) {
        JsonNode node;
        try {
            node = objectMapper.readTree(Files.readString(marker));
        } catch (IOException e) {
            return new ContractCheck(Status.INVALID,
                    List.of("完成契约标记解析失败: " + e.getMessage()), workEvidence);
        }
        List<String> failures = new ArrayList<>();
        JsonNode deliverables = node.get("deliverables");
        if (deliverables == null || !deliverables.isArray() || deliverables.isEmpty()) {
            failures.add("契约缺少 deliverables 清单（空数组视为未声明任何交付件）");
        } else {
            for (int i = 0; i < deliverables.size(); i++) {
                verifyDeliverable(root, deliverables.get(i), i, failures);
            }
        }
        if (failures.isEmpty()) {
            return new ContractCheck(Status.VERIFIED, List.of(), workEvidence);
        }
        return new ContractCheck(Status.INVALID, List.copyOf(failures), workEvidence);
    }

    private void verifyDeliverable(Path root, JsonNode item, int index, List<String> failures) {
        String label = "deliverables[" + index + "]";
        JsonNode pathNode = item.get("path");
        if (pathNode == null || pathNode.asText("").isBlank()) {
            failures.add(label + ": 缺少 path");
            return;
        }
        String declared = pathNode.asText().strip();
        Path file = root.resolve(declared).normalize();
        if (!Files.isRegularFile(file)) {
            // 相对路径未命中再试绝对路径（模型跨会话引用绝对路径是既有习惯）
            Path absolute = Path.of(declared);
            file = Files.isRegularFile(absolute) ? absolute : file;
        }
        if (!Files.isRegularFile(file)) {
            failures.add(label + " (" + declared + "): 交付件不存在");
            return;
        }
        long minBytes = item.path("minBytes").asLong(1);
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            failures.add(label + " (" + declared + "): 无法读取文件大小");
            return;
        }
        if (size < Math.max(minBytes, 1)) {
            failures.add(label + " (" + declared + "): 文件 " + size + "B 低于声明下限 " + minBytes + "B");
        }
        JsonNode shaNode = item.get("sha256");
        if (shaNode != null && !shaNode.isNull()) {
            String actual = sha256(file);
            String expected = shaNode.asText().strip().toLowerCase();
            if (actual == null) {
                failures.add(label + " (" + declared + "): 指纹计算失败");
            } else if (!actual.equals(expected)) {
                failures.add(label + " (" + declared + "): sha256 不匹配（落件后被修改未重新声明）");
            }
        }
    }

    private static boolean planFileExists(Path root) {
        for (String dir : PLAN_DIRS) {
            Path planDir = root.resolve(dir);
            if (!Files.isDirectory(planDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(planDir)) {
                if (files.anyMatch(p -> Files.isRegularFile(p)
                        && p.getFileName().toString().toLowerCase().endsWith(".md"))) {
                    return true;
                }
            } catch (IOException | UncheckedIOException e) {
                log.debug("Plan dir scan failed: {}", planDir, e);
            }
        }
        return false;
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException | UncheckedIOException e) {
            return null;
        }
    }
}
