package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.FileAgentDraft;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * 文件驱动智能体试运行的工作区装配（智能体在线调试，2026-09-24）。
 *
 * <p>把草稿内容写进临时定义目录 {@code runtimes/{agentId}/.debug-draft/}
 * （AGENTS.md + subagents/*.md 按草稿；skills 从真实定义目录只读拷贝，草稿
 * 不涉及技能树），返回 workspacePath 指向临时目录的克隆配置——后续装配与
 * 发布流程完全同构（AGENTS.md 注入、copyDefinitionSkills 都读该目录），缓存
 * 键由调用方带 {@code draft=<draftSeq>} 与发布装配隔离。目录点前缀符合运行态
 * 清理「排除点开头目录」的约定。</p>
 */
@Component
public class FileDebugWorkspaceManager {

    private final SessionWorkspaceManager sessionWorkspaceManager;

    public FileDebugWorkspaceManager(SessionWorkspaceManager sessionWorkspaceManager) {
        this.sessionWorkspaceManager = sessionWorkspaceManager;
    }

    /** 按草稿准备临时定义目录并返回指向它的克隆配置。 */
    public AgentConfig buildDebugConfig(AgentConfig config, FileAgentDraft draft) {
        Path tempDir = sessionWorkspaceManager
                .resolveRuntimeRoot(config.getId().toString()).resolve(".debug-draft");
        Path realDefDir = sessionWorkspaceManager
                .resolveAgentDefinitionDir(config.getWorkspacePath());
        try {
            deleteRecursively(tempDir);
            Files.createDirectories(tempDir);
            Files.writeString(tempDir.resolve("AGENTS.md"), draft.getAgentsMd(), StandardCharsets.UTF_8);
            Map<String, String> subs = draft.getSubagents() == null
                    ? Map.of() : draft.getSubagents();
            if (!subs.isEmpty()) {
                Path subDir = tempDir.resolve("subagents");
                Files.createDirectories(subDir);
                for (Map.Entry<String, String> e : subs.entrySet()) {
                    Files.writeString(subDir.resolve(e.getKey()),
                            e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8);
                }
            }
            Path skills = realDefDir.resolve("skills");
            if (Files.isDirectory(skills)) {
                copyDirectory(skills, tempDir.resolve("skills"));
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "debug_draft_prepare_failed",
                    "准备文件智能体试运行目录失败：" + e.getMessage());
        }
        AgentConfig copy = new AgentConfig();
        copy.setId(config.getId());
        copy.setName(config.getName());
        copy.setStatus(config.getStatus());
        copy.setModel(config.getModel());
        copy.setPlanEnabled(config.isPlanEnabled());
        copy.setSkillSedimentation(config.isSkillSedimentation());
        copy.setFileBased(true);
        copy.setWorkspacePath(SessionWorkspaceManager.RUNTIMES_DIR
                + "/" + config.getId() + "/.debug-draft");
        return copy;
    }

    /** 递归删除（仅用于 .debug-draft 临时目录，非用户数据面）。 */
    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** 目录树拷贝（试运行 skills 只读副本）。 */
    private void copyDirectory(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (var walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
