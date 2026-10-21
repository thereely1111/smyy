package com.tipdm.agentplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 运行态产物兜底回收：扫描 runtimes/{agentId}/{sessionId} 目录（两层布局，
 * 与 {@link SessionWorkspaceManager} 一致），删除超过
 * {@code runtime-gc-max-age-days} 天未更新的会话目录，防止 main-* 无限堆积。
 * 默认关闭（{@code agent-platform.runtime-gc-enabled=false}），由运维按需开启。
 *
 * <p><strong>只回收会话目录</strong>：{@code runtimes/{agentId}/} 下还有装配期写入的共享目录
 * {@code .harness}（技能副本 + harness 工作区，见 {@link SessionWorkspaceManager}）。
 * 它只在装配/技能变更时写入、mtime 往往很旧，若按时间一并回收，会让该 Agent 的
 * 所有会话失去运行环境。故按「跳过点开头目录」排除，见 {@link #isReclaimableSessionDir}。</p>
 *
 * <p>仅依据目录最后修改时间判断（清晰可预期）；如需与 Postgres 会话状态
 * 交叉校验可在此扩展。</p>
 */
@Component
public class RuntimeWorkspaceGc {

    private static final Logger log = LoggerFactory.getLogger(RuntimeWorkspaceGc.class);

    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final boolean enabled;
    private final long maxAgeDays;

    public RuntimeWorkspaceGc(SessionWorkspaceManager sessionWorkspaceManager,
                              @Value("${agent-platform.runtime-gc-enabled:false}") boolean enabled,
                              @Value("${agent-platform.runtime-gc-max-age-days:30}") long maxAgeDays) {
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.enabled = enabled;
        this.maxAgeDays = Math.max(1, maxAgeDays);
    }

    @Scheduled(cron = "${agent-platform.runtime-gc-cron:0 0 3 * * *}")
    public void gc() {
        if (!enabled) {
            return;
        }
        Path runtimes = sessionWorkspaceManager.getRuntimesRoot();
        if (!Files.isDirectory(runtimes)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(maxAgeDays));
        int removed = 0;
        try (Stream<Path> agentLevel = Files.list(runtimes)) {
            for (Path agentDir : agentLevel.filter(Files::isDirectory).toList()) {
                try (Stream<Path> sessionLevel = Files.list(agentDir)) {
                    for (Path sessionDir : sessionLevel.filter(Files::isDirectory)
                            .filter(RuntimeWorkspaceGc::isReclaimableSessionDir)
                            .toList()) {
                        if (isExpired(sessionDir, cutoff)) {
                            deleteRecursively(sessionDir);
                            removed++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Runtime workspace GC failed: {}", e.getMessage());
            return;
        }
        if (removed > 0) {
            log.info("Runtime workspace GC removed {} session dir(s) (older than {}d)", removed, maxAgeDays);
        }
    }

    /**
     * 是否为可回收的会话目录。
     *
     * <p>排除点开头的共享目录（{@code .harness} 等）——它们不是会话产物，
     * 且 mtime 长期不变，按时间判定必然"过期"，删掉会波及该 Agent 的全部会话。</p>
     */
    private static boolean isReclaimableSessionDir(Path dir) {
        Path name = dir.getFileName();
        return name != null && !name.toString().startsWith(".");
    }

    private boolean isExpired(Path dir, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("Failed to GC session dir {}: {}", dir, e.getMessage());
        }
    }
}
