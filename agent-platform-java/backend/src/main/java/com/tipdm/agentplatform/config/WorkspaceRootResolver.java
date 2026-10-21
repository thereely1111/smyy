package com.tipdm.agentplatform.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 运行态根目录（{@code agent-platform.workspace-root}）的自推导实现。
 *
 * <p><b>为什么需要它</b>：默认值原来是 {@code ${user.dir}/workspace}，
 * 即「启动目录的相对路径」。后端一次搬迁（启动目录从仓库根挪到 {@code backend/} 上层）
 * 就会让 workspace-root 静默漂移到另一个目录：老会话的 {@code uploads/}、{@code artifacts/}
 * 全留在旧根里，模型与盘面取证都按新根找 ⇒ 表现为「我明明传了，它却读不到」。
 * 同一份代码在不同设备上仓库落在不同盘符/目录，任何写死的绝对路径都不可移植。</p>
 *
 * <p><b>判定规则（不含任何绝对路径字面量）</b>：从启动目录逐级向上找「仓库根」——
 * 该目录必须同时含 {@code backend/pom.xml}（文件）与 {@code workspace/agents}（目录）。
 * 命中则 workspace-root = {@code <仓库根>/workspace}；否则回落到
 * {@code <启动目录>/workspace}（= 原行为，兼容「只部署 jar + 同级 workspace」的形态）。</p>
 *
 * <p>刻意<b>不</b>把「含 {@code workspace/agents}」单独作为判据：{@code backend/workspace/}
 * 里也存在 {@code agents/_default}，只按它判会让从 {@code backend/} 启动时错误地把
 * {@code backend/workspace} 当成仓库 workspace。</p>
 */
public final class WorkspaceRootResolver {

    /** 仓库根标记：任一仓库根都必须含 {@code backend/pom.xml}。 */
    static final String REPO_MARKER_RELATIVE = "backend/pom.xml";

    /** 仓库根标记之二：定义区 {@code workspace/agents}。 */
    static final String WORKSPACE_AGENTS_RELATIVE = "workspace/agents";

    /** 向上探测层数上限（防极端路径下无限上溯）。 */
    static final int MAX_UPWARD_LEVELS = 8;

    /** workspace 目录名（相对仓库根 / 启动目录）。 */
    static final String WORKSPACE_DIR_NAME = "workspace";

    private WorkspaceRootResolver() {
    }

    /**
     * 从启动目录起向上探测仓库根。
     *
     * @param startDir 启动目录（通常为 {@code System.getProperty("user.dir")}）
     * @return 命中的仓库根；找不到返回 {@link Optional#empty()}
     */
    public static Optional<Path> detectRepoRoot(Path startDir) {
        if (startDir == null) {
            return Optional.empty();
        }
        Path cursor = startDir.toAbsolutePath().normalize();
        for (int level = 0; level <= MAX_UPWARD_LEVELS && cursor != null; level++) {
            if (isRepoRoot(cursor)) {
                return Optional.of(cursor);
            }
            Path parent = cursor.getParent();
            // 已到盘符根（parent 与自身相同）则停止
            if (parent == null || parent.equals(cursor)) {
                break;
            }
            cursor = parent;
        }
        return Optional.empty();
    }

    /** 判定某目录是否为仓库根：{@code backend/pom.xml} 是文件且 {@code workspace/agents} 是目录。 */
    public static boolean isRepoRoot(Path candidate) {
        if (candidate == null) {
            return false;
        }
        Path root = candidate.toAbsolutePath().normalize();
        boolean hasBackendPom = Files.isRegularFile(root.resolve(REPO_MARKER_RELATIVE));
        boolean hasWorkspaceAgents = Files.isDirectory(root.resolve(WORKSPACE_AGENTS_RELATIVE));
        return hasBackendPom && hasWorkspaceAgents;
    }

    /**
     * 解析 workspace-root：优先仓库根下的 {@code workspace}，否则回落启动目录下的 {@code workspace}。
     *
     * @param startDir 启动目录
     * @return 归一化后的绝对路径（不保证目录已存在，Boot 会按需创建）
     */
    public static Path resolveWorkspaceRoot(Path startDir) {
        Path raw = (startDir == null) ? Path.of("").toAbsolutePath() : startDir.toAbsolutePath();
        final Path base = raw.normalize();
        return detectRepoRoot(base)
                .map(root -> root.resolve(WORKSPACE_DIR_NAME))
                .orElseGet(() -> base.resolve(WORKSPACE_DIR_NAME));
    }

    /**
     * 解析落盘日志路径：优先仓库根下的 {@code logs/backend.log}，否则回落启动目录。
     *
     * @param startDir 启动目录
     * @return 归一化后的绝对路径；无法定位仓库根时返回 {@link Optional#empty()}
     */
    public static Optional<Path> resolveLogFile(Path startDir) {
        return detectRepoRoot(startDir)
                .map(root -> root.resolve("logs").resolve("backend.log"));
    }

    /** 当前进程的启动目录（{@code user.dir}），缺失时用 JVM 默认工作目录兜底。 */
    public static Path currentStartDir() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null || userDir.isBlank()) {
            return Path.of("").toAbsolutePath().normalize();
        }
        return Path.of(userDir).toAbsolutePath().normalize();
    }
}
