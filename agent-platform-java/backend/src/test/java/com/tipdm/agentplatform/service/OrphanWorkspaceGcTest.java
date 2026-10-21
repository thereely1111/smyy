package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.repository.AgentConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 孤儿工作区回收的两道安全门。
 *
 * <p>这道任务会<b>真实删磁盘目录</b>，判据错一次就是误删用户数据，故固化：
 * 只删「UUID 形态目录名 + DB 无行 + 超过宽限期」，
 * 人取名的文件驱动智能体目录与DB 有行的目录一律不动。</p>
 */
class OrphanWorkspaceGcTest {

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);

    private OrphanWorkspaceGc gc(Path root, boolean enabled, long minAgeHours) {
        return new OrphanWorkspaceGc(root.toString(), repository, enabled, minAgeHours);
    }

    private static Path orphanWithAge(Path root, UUID id, Duration age) throws IOException {
        Path dir = Files.createDirectories(root.resolve("agents/" + id));
        Files.writeString(dir.resolve("AGENTS.md"), "# 案例开发工具\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(age)));
        return dir;
    }

    @Test
    void removesOrphanUuidDirectoryOlderThanGracePeriod(@TempDir Path root) throws IOException {
        UUID id = UUID.randomUUID();
        Path dir = orphanWithAge(root, id, Duration.ofHours(48));
        when(repository.existsById(any())).thenReturn(false);

        gc(root, true, 24).gc();

        assertFalse(Files.exists(dir), "DB 无行且超宽限期的UUID 目录应被回收");
    }

    @Test
    void keepsOrphanWithinGracePeriod(@TempDir Path root) throws IOException {
        // 刚写盘、事务尚未提交的瞬间：宽限期是防误删的关键
        UUID id = UUID.randomUUID();
        Path dir = orphanWithAge(root, id, Duration.ofMinutes(5));
        when(repository.existsById(any())).thenReturn(false);

        gc(root, true, 24).gc();

        assertTrue(Files.exists(dir), "宽限期内的目录不得删除（可能是事务未提交）");
    }

    @Test
    void keepsDirectoryWhoseDbRowStillExists(@TempDir Path root) throws IOException {
        UUID id = UUID.randomUUID();
        Path dir = orphanWithAge(root, id, Duration.ofHours(48));
        when(repository.existsById(id)).thenReturn(true);

        gc(root, true, 24).gc();

        assertTrue(Files.exists(dir), "DB 仍有行的目录是正常智能体工作区，不得删除");
    }

    @Test
    void neverTouchesHumanNamedFileAgentDirectory(@TempDir Path root) throws IOException {
        // 真文件驱动智能体：目录名非UUID，DB 永远没有它的行
        Path dir = Files.createDirectories(root.resolve("agents/教材开发小助手"));
        Files.writeString(dir.resolve("AGENTS.md"),
                "---\ntags: [教育培训]\n---\n\n# 编排智能体\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(Duration.ofHours(48))));
        when(repository.existsById(any())).thenReturn(false);

        gc(root, true, 24).gc();

        assertTrue(Files.exists(dir), "人取名的文件驱动智能体目录永不自动删除");
    }

    @Test
    void doesNothingWhenDisabled(@TempDir Path root) throws IOException {
        UUID id = UUID.randomUUID();
        Path dir = orphanWithAge(root, id, Duration.ofHours(48));
        when(repository.existsById(any())).thenReturn(false);

        gc(root, false, 24).gc();

        assertTrue(Files.exists(dir), "默认关闭，未显式开启不得删任何目录");
    }
}