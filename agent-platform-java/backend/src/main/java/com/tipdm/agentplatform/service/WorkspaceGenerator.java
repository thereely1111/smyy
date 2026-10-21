package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.Subagent;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.Tool;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * T2.2 工作区生成器：根据 Agent 配置生成 workspace/agents/{id}/ 下的 AGENTS.md、skills/。
 * 子 Agent 现配为数据库存储（agent_configs.inline_subagents），不再生成 subagents/ 定义文件。
 */
@Service
public class WorkspaceGenerator {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceGenerator.class);

    private final Path workspaceRoot;

    public WorkspaceGenerator(@Value("${agent-platform.workspace-root}") String workspaceRoot) {
        this.workspaceRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize();
    }

    /**
     * 生成（或重建）Agent 工作区，返回相对 workspaceRoot 的路径（如 agents/{id}）。
     * 只重建定义文件（AGENTS.md、skills/、plan/），不触碰运行态产物
     * （runtimes/ 与模型残留文件），从根上避免「保存配置清空用户会话产物」。
     */
    public String generate(AgentConfig config, List<Skill> skills, List<Tool> tools) {
        Path agentDir = workspaceRoot.resolve("agents").resolve(config.getId().toString()).normalize();
        if (!agentDir.startsWith(workspaceRoot)) {
            throw ApiException.badRequest("非法工作区路径");
        }
        try {
            // 精确清理已知定义文件/目录，其他文件（模型残留等）保留并告警
            deleteDefinitionFiles(agentDir);
            Files.createDirectories(agentDir.resolve("skills"));

            Files.writeString(agentDir.resolve("AGENTS.md"), renderAgentsMd(config, skills, tools),
                    StandardCharsets.UTF_8);
            for (Skill skill : skills) {
                Path target = agentDir.resolve("skills").resolve(skill.getId() + ".md");
                Files.writeString(target, renderSkill(skill), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to generate workspace for agent {}", config.getId(), e);
            throw ApiException.badRequest("工作区生成失败: " + e.getMessage());
        }

        String relative = workspaceRoot.relativize(agentDir).toString();
        config.setWorkspacePath(relative);
        return relative;
    }

    /**
     * 只删除定义区中平台生成的已知文件/目录；目录中存在其他内容（模型残留、
     * 非平台文件）时保留并记录告警，避免误删。
     */
    private void deleteDefinitionFiles(Path agentDir) throws IOException {
        if (!Files.exists(agentDir)) {
            return;
        }
        Files.deleteIfExists(agentDir.resolve("AGENTS.md"));
        deleteIfExistsRecursively(agentDir.resolve("skills"));
        deleteIfExistsRecursively(agentDir.resolve("subagents"));
        deleteIfExistsRecursively(agentDir.resolve("plan"));
        try (Stream<Path> walk = Files.walk(agentDir)) {
            List<Path> leftovers = walk.filter(Files::isRegularFile)
                    .filter(p -> !agentDir.relativize(p).toString().startsWith("skills"))
                    .filter(p -> !agentDir.relativize(p).toString().startsWith("subagents"))
                    .toList();
            for (Path leftover : leftovers) {
                log.warn("Workspace leftover kept (not managed by platform): {}", leftover);
            }
        }
    }

    private void deleteIfExistsRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    public Path resolveWorkspace(String workspacePath) {
        String resolved = workspacePath == null || workspacePath.isBlank()
                ? "agents/_default"
                : workspacePath;
        Path path = workspaceRoot.resolve(resolved).normalize();
        if (!path.startsWith(workspaceRoot)) {
            throw ApiException.badRequest("非法工作区路径: " + workspacePath);
        }
        return path;
    }

    private String renderAgentsMd(AgentConfig config, List<Skill> skills, List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(config.getName()).append("\n\n");
        if (config.getDescription() != null && !config.getDescription().isBlank()) {
            sb.append(config.getDescription()).append("\n\n");
        }
        sb.append("## 系统提示词\n\n")
                .append(config.getSystemPrompt() == null ? "" : config.getSystemPrompt())
                .append("\n\n");
        sb.append("## 技能\n\n");
        if (skills.isEmpty()) {
            sb.append("- （无）\n");
        }
        for (Skill skill : skills) {
            sb.append("- ").append(skill.getName()).append("：")
                    .append(skill.getDescription() == null ? "" : skill.getDescription()).append("\n");
        }
        sb.append("\n## 工具\n\n");
        if (tools.isEmpty()) {
            sb.append("- （无）\n");
        }
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append("（").append(tool.getToolType()).append("）：")
                    .append(tool.getDescription() == null ? "" : tool.getDescription()).append("\n");
        }
        sb.append("\n## 子 Agent\n\n");
        List<Subagent> subagents = config.getInlineSubagents() == null ? List.of() : config.getInlineSubagents();
        if (subagents.isEmpty()) {
            sb.append("- （无）\n");
        }
        for (Subagent subagent : subagents) {
            sb.append("- ").append(subagent.name()).append("：")
                    .append(subagent.description() == null ? "" : subagent.description()).append("\n");
        }
        return sb.toString();
    }

    private String renderSkill(Skill skill) {
        return "# " + skill.getName() + "\n\n"
                + (skill.getDescription() == null ? "" : skill.getDescription()) + "\n\n"
                + "实现类型: " + skill.getImplementationType() + "\n";
    }

}
