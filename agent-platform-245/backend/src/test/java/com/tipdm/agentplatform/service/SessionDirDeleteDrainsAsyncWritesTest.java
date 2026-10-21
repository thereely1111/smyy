package com.tipdm.agentplatform.service;

import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除进行中的会话时，异步 memory flush 不得把已删目录重建回来。
 *
 * <p><b>缺陷机制</b>：框架把 memory 提取挂在 {@code boundedElastic} 上 fire-and-forget
 * （{@code MemoryFlushMiddleware} 在 {@code doOnComplete} 里调度），中断一个在跑的 run
 * 就会排下一次 flush。该flush 写盘用的是 {@code appendUtf8WorkspaceRelative}，
 * <b>自动创建父目录</b>。于是删除日志打印「目录已删」，几十秒后磁盘上又长出
 * {@code runtimes/{agentId}/{sessionId}/memory/YYYY-MM-DD.md} —— 实测删除后 82 秒复现。
 *
 * <p>会话墓碑挡不住这条路径：墓碑否决的是「发起新的唤醒」，而这里的写盘任务
 * 在删除动作<b>之前</b>就已排队，它不看墓碑、也不经过任何唤醒入口。
 * 框架为此提供了两个排空计数（{@code SessionTree} 镜像写、{@code MemoryBackgroundTasks}
 * flush），其自身在 {@code HarnessAgent.close()} 释放资源前也是这么排空的。
 *
 * <p>本测试锁死契约：删除会话目录前必须等在飞的异步写盘排空。</p>
 */
class SessionDirDeleteDrainsAsyncWritesTest {

    private static final String AGENT_ID = "agent-async-write";
    private static final String SESSION_ID = "main-inflight";

    /**
     * 核心回归：删除时有一个「在飞的异步写盘」正等着落盘，
     * 删除动作必须等它结束，于是目录不会被重建。
     *
     * <p>模拟方式与框架一致：{@code MemoryBackgroundTasks.begin()} 由调度点调用、
     * 任务体真正落盘后才 {@code end()}。若删除路径不排空，
     * 任务体会在删除之后执行并把目录写回来。</p>
     */
    @Test
    void deletingSessionWaitsForInFlightAsyncWrite(@TempDir Path root) throws Exception {
        SessionWorkspaceManager manager = new SessionWorkspaceManager(root.toString());
        Path sessionDir = manager.ensureSessionDir(AGENT_ID, SESSION_ID);
        Path memoryFile = sessionDir.resolve("memory").resolve("2026-10-13.md");

        CountDownLatch writeStarted = new CountDownLatch(1);
        AtomicBoolean writeCompleted = new AtomicBoolean(false);
        AtomicBoolean dirExistedWhenWriting = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            MemoryBackgroundTasks.begin();
            try {
                // 对齐框架：任务体在调度之后异步执行，此刻删除动作正在推进。
                writeStarted.countDown();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // appendUtf8WorkspaceRelative 会自动创建父目录——这正是目录被重建的原因。
                dirExistedWhenWriting.set(Files.exists(sessionDir));
                Files.createDirectories(memoryFile.getParent());
                Files.writeString(memoryFile, "## Memory Flush\n- late write\n");
                writeCompleted.set(true);
            } catch (Exception e) {
                // 落盘失败不影响断言：目录是否被重建才是被测行为。
            } finally {
                MemoryBackgroundTasks.end();
            }
        });
        writer.start();
        assertTrue(writeStarted.await(2, TimeUnit.SECONDS), "async writer did not start");

        manager.deleteSessionDir(AGENT_ID, null, SESSION_ID);
        writer.join(5_000);

        // 前提：这次异步写盘真的执行了（否则下面断言无意义）。
        assertTrue(writeCompleted.get(), "async write must actually have run");
        // 被测行为：目录必须已被删除、不得被写回来。
        // 修复前该写盘发生在删除之后（dirExisted=false）并重建了目录 ⇒ 此断言失败。
        assertFalse(
                Files.exists(sessionDir),
                "deleted session dir must stay deleted; an in-flight async write recreated it"
                        + " (dirExistedWhenWriting=" + dirExistedWhenWriting.get() + ")");
    }

    /**
     * 无在飞任务时删除照常快速完成 —— 排空不能把正常删除拖慢。
     */
    @Test
    void deletingSessionWithoutInFlightWritesReturnsPromptly(@TempDir Path root) throws Exception {
        SessionWorkspaceManager manager = new SessionWorkspaceManager(root.toString());
        Path sessionDir = manager.ensureSessionDir(AGENT_ID, SESSION_ID);
        Files.writeString(sessionDir.resolve("artifact.md"), "x");

        long start = System.nanoTime();
        manager.deleteSessionDir(AGENT_ID, null, SESSION_ID);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertFalse(Files.exists(sessionDir), "session dir must be deleted");
        assertTrue(elapsedMs < 3_000, "idle delete took " + elapsedMs + "ms; quiescence wait is not short-circuiting");
    }

    /**
     * 目录不存在时删除必须幂等（排空逻辑不得引入异常），
     * 这是唤醒链路重复调用删除时的既有契约。
     */
    @Test
    void deletingMissingSessionDirIsIdempotent(@TempDir Path root) {
        SessionWorkspaceManager manager = new SessionWorkspaceManager(root.toString());
        manager.deleteSessionDir(AGENT_ID, null, "main-never-existed");
    }

    /**
     * 核心回归之二：<b>写盘耗时长于同步等待上限</b>时，目录仍必须最终被删除。
     *
     * <p>这是真实故障的形态。在飞的 memory maintenance 里含一次模型调用
     * （{@code MemoryConsolidator.consolidate} 用 LLM 合并当日记忆），实测 18s，
     * 而同步排空上限只有 5s。于是「先等 5s→ 删目录」之后那次写盘才落盘，
     * 把已删目录连同父目录一起重建 —— 删��日志已打印，磁盘上却又长回来。
     *
     * <p><b>所以修法不能是「把上限调大」</b>：模型调用时长不可预测，任何固定上限都是赌。
     * 契约是：同步删一次（快路径），排不干净时<b>把删除推迟到异步线程</b>，
     * 等计数真正归零后再删一次 —— 用户请求不被模型时长拖住，目录最终一定消失。
     *
     * <p>耗时取 {@code 7s}（大于 5s 上限、小于 120s 补删上限），
     * 断言只锁最终状态，不断言「同步阶段就已删干净」。
     */
    @Test
    void longRunningAsyncWriteStillLeavesSessionDirDeleted(@TempDir Path root) throws Exception {
        SessionWorkspaceManager manager = new SessionWorkspaceManager(root.toString());
        Path sessionDir = manager.ensureSessionDir(AGENT_ID, "main-slow-write");
        Path memoryFile = sessionDir.resolve("memory").resolve("2026-10-13.md");
        Path memoryMd = sessionDir.resolve("MEMORY.md");

        CountDownLatch writeStarted = new CountDownLatch(1);
        AtomicBoolean writeCompleted = new AtomicBoolean(false);
        // 对齐 MemoryConsolidator：先做一次慢「模型调用」，再写 MEMORY.md + 状态文件。
        Thread writer = new Thread(() -> {
            MemoryBackgroundTasks.begin();
            try {
                writeStarted.countDown();
                Thread.sleep(7_000);
                // 写盘会自动创建父目录——这正是目录被重建的原因。
                Files.createDirectories(memoryFile.getParent());
                Files.writeString(memoryMd, "# MEMORY\n");
                Files.writeString(memoryFile, "## Memory Flush\n- late write\n");
                Files.writeString(memoryFile.getParent().resolve(".consolidation_state"), "ts");
                writeCompleted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 落盘失败不影响断言：目录是否被重建才是被测行为。
            } finally {
                MemoryBackgroundTasks.end();
            }
        });
        writer.start();
        assertTrue(writeStarted.await(2, TimeUnit.SECONDS), "async writer did not start");

        // 同步删除不该被模型时长拖住（否则用户要干等十几秒）。
        long start = System.nanoTime();
        manager.deleteSessionDir(AGENT_ID, null, "main-slow-write");
        long syncElapsedMs = (System.nanoTime() - start) / 1_000_000L;

        writer.join(20_000);
        assertTrue(writeCompleted.get(), "async write must actually have run");

        // 异步补删是另起线程完成的，给它一点时间落定。
        long deadline = System.currentTimeMillis() + 15_000;
        while (Files.exists(sessionDir) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertTrue(syncElapsedMs < 7_000,
                "sync delete must not block on the slow async write; took " + syncElapsedMs + "ms");
        assertFalse(Files.exists(sessionDir),
                "deleted session dir must stay deleted even when the async write outlasts the sync wait"
                        + " (it must be re-deleted after the write finishes)");
        assertFalse(Files.exists(memoryFile), "late memory flush file must not survive");
        assertFalse(Files.exists(memoryMd), "late MEMORY.md must not survive");
    }
}