package com.tipdm.agentplatform.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link WorkspaceRootResolver} 的单元用例：全部基于临时目录构造，
 * 不依赖本机盘符/绝对路径，任何设备上都能跑。
 */
class WorkspaceRootResolverTest {

    /** 造一个「仓库根」：backend/pom.xml + workspace/agents 齐备。 */
    private static Path makeRepoRoot(Path base) throws IOException {
        Files.createDirectories(base.resolve("backend"));
        Files.createFile(base.resolve("backend").resolve("pom.xml"));
        Files.createDirectories(base.resolve("workspace").resolve("agents"));
        return base;
    }

    @Test
    @DisplayName("从仓库根启动：命中仓库根，workspace-root = <仓库根>/workspace")
    void detectFromRepoRoot(@TempDir Path tmp) throws IOException {
        Path repo = makeRepoRoot(tmp.resolve("repo"));

        assertEquals(Optional.of(repo), WorkspaceRootResolver.detectRepoRoot(repo));
        assertEquals(repo.resolve("workspace"), WorkspaceRootResolver.resolveWorkspaceRoot(repo));
    }

    @Test
    @DisplayName("从 backend 模块目录启动：向上找到仓库根（搬迁后最容易踩的形态）")
    void detectFromBackendModule(@TempDir Path tmp) throws IOException {
        Path repo = makeRepoRoot(tmp.resolve("repo"));

        assertEquals(Optional.of(repo), WorkspaceRootResolver.detectRepoRoot(repo.resolve("backend")));
        assertEquals(repo.resolve("workspace"),
                WorkspaceRootResolver.resolveWorkspaceRoot(repo.resolve("backend")));
    }

    @Test
    @DisplayName("陷阱：backend/workspace/agents/_default 不能被误判成仓库 workspace")
    void backendWorkspaceTrap(@TempDir Path tmp) throws IOException {
        Path repo = makeRepoRoot(tmp.resolve("repo"));
        // backend 自己的 workspace（含 agents/_default）——只看「含 workspace/agents」就会误判
        Files.createDirectories(repo.resolve("backend").resolve("workspace")
                .resolve("agents").resolve("_default"));

        Path detected = WorkspaceRootResolver.resolveWorkspaceRoot(repo.resolve("backend"));

        assertEquals(repo.resolve("workspace"), detected);
        assertFalse(detected.equals(repo.resolve("backend").resolve("workspace")));
    }

    @Test
    @DisplayName("找不到仓库根：回落 <启动目录>/workspace（兼容只部署 jar 的形态）")
    void fallbackToStartDir(@TempDir Path tmp) throws IOException {
        Path standalone = tmp.resolve("opt").resolve("app");
        Files.createDirectories(standalone);

        assertTrue(WorkspaceRootResolver.detectRepoRoot(standalone).isEmpty());
        assertEquals(standalone.resolve("workspace"),
                WorkspaceRootResolver.resolveWorkspaceRoot(standalone));
    }

    @Test
    @DisplayName("落盘日志：<仓库根>/logs/backend.log；无仓库根则空")
    void resolveLogFile(@TempDir Path tmp) throws IOException {
        Path repo = makeRepoRoot(tmp.resolve("repo"));

        assertEquals(Optional.of(repo.resolve("logs").resolve("backend.log")),
                WorkspaceRootResolver.resolveLogFile(repo.resolve("backend")));
        assertTrue(WorkspaceRootResolver.resolveLogFile(tmp.resolve("standalone")).isEmpty());
    }

    @Test
    @DisplayName("真实布局冒烟：本仓启动目录能推出一个含 workspace/agents 的根")
    void realLayoutSmoke() {
        Path detected = WorkspaceRootResolver.resolveWorkspaceRoot(WorkspaceRootResolver.currentStartDir());
        assumeTrue(Files.isDirectory(detected.resolve("agents")),
                "当前工作目录不是本仓构建形态，跳过（不算失败）");

        assertTrue(Files.isDirectory(detected.resolve("agents")));
    }
}
