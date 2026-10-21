package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.repository.AgentConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 孤儿工作区兜底回收：删除 {@code agents/{agentId}/} 下「DB 行已不存在」的目录。
 *
 * <p><strong>背景</strong>（2026-10-10，K8s 测试环境取证）：数据库型智能体的工作区目录名
 * 就是它的 agentId（{@code agents/{uuid}/}，由 {@code WorkspaceGenerator.generate} 按主键建目录）。
 * 删除智能体时若清盘失败、或创建流程写盘后事务回滚，都会留下这类目录；此前既没有补偿也没有
 * 兜底回收，残留目录会被文件驱动加载器登记成「UUID 名字 + 发布中 + 无描述」的假智能体
 * （实测76 个目录中 72 个是这类残留，且在会话表里零引用，从未被使用过）。</p>
 *
 * <p><strong>只删 UUID 形态且 DB 无行的目录</strong>，两道安全门：</p>
 * <ol>
 *   <li>目录名必须是 UUID 形态——人取名的目录是真正的文件驱动智能体，
 *       它们的 id 是 {@code UUID.nameUUIDFromBytes(目录名)} 派生值而非目录名，
 *       无论 DB 有没有对应行都不能删；</li>
 *   <li>DB 必须查不到该 id——行还在说明只是暂时读不到或事务未提交。</li>
 * </ol>
 *
 * <p>另有 <strong>宽限期</strong>（{@code orphan-workspace-gc-min-age-hours}，默认 24 小时），
 * 避免误删刚被创建、事务尚未提交的那一瞬。默认关闭（{@code orphan-workspace-gc-enabled=false}），
 * 由运维按需开启。</p>
 */
@Component
public class OrphanWorkspaceGc {

    private static final Logger log = LoggerFactory.getLogger(OrphanWorkspaceGc.class);

    private final Path agentsRoot;
    private final AgentConfigRepository configRepository;
    private final boolean enabled;
    private final Duration minAge;

    public OrphanWorkspaceGc(
            @Value("${agent-platform.workspace-root}") String workspaceRoot,
            AgentConfigRepository configRepository,
            @Value("${agent-platform.orphan-workspace-gc-enabled:false}") boolean enabled,
            @Value("${agent-platform.orphan-workspace-gc-min-age-hours:24}") long minAgeHours) {
        this.agentsRoot = Paths.get(workspaceRoot).resolve("agents").toAbsolutePath().normalize();
        this.configRepository = configRepository;
        this.enabled = enabled;
        this.minAge = Duration.ofHours(Math.max(1, minAgeHours));
    }

    @Scheduled(cron = "${agent-platform.orphan-workspace-gc-cron:0 30 4 * * *}")
    public void gc() {
        if (!enabled) {
            return;
        }
        if (!Files.isDirectory(agentsRoot)) {
            return;
        }
        List<Path> removed = new ArrayList<>();
        try (Stream<Path> stream = Files.list(agentsRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                UUID id = parseUuidOrNull(name);
                if (id == null) {
                    // 人取名的文件驱动智能体目录：永不自动删除
                    continue;
                }
                if (configRepository.existsById(id)) {
                    continue;
                }
                if (!isOlderThan(dir, minAge)) {
                    log.info("Orphan workspace {} is newer than grace period, skip this round", name);
                    continue;
                }
                if (deleteRecursively(dir)) {
                    removed.add(dir);
                }
            }
        } catch (IOException e) {
            log.warn("Orphan workspace GC scan failed: {}", e.getMessage());
            return;
        }
        if (!removed.isEmpty()) {
            log.info("Orphan workspace GC removed {} director(ies) with no DB row: {}",
                    removed.size(), removed.stream()
                            .map(p -> p.getFileName().toString()).toList());
        }
    }

    /** 严格解析：{@code UUID.fromString} 在部分实现里对非规范串也能过，这里用格式正则兜一层。 */
    private static UUID parseUuidOrNull(String name) {
        if (name.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isOlderThan(Path dir, Duration minAge) throws IOException {
        return Files.getLastModifiedTime(dir).toInstant().isBefore(Instant.now().minus(minAge));
    }

    /** @return 是否删除成功（失败只记日志，不中断整轮扫描）。 */
    private static boolean deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to remove orphan workspace {}: {}", dir, e.getMessage());
            return false;
        }
    }
}
