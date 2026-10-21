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
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T2.2 工作区生成器：根据 Agent 配置生成 workspace/agents/{id}/ 下的 AGENTS.md、
 * skills/&lt;name&gt;/SKILL.md、subagents/&lt;id&gt;.md。智能体目录是智能体定义的唯一真相源，
 * 框架按目录约定自动发现其中三类内容（装配期 {@code builder.workspace()} 指向此目录）。
 */
@Service
public class WorkspaceGenerator {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceGenerator.class);

    /**
     * 子智能体名称白名单（裸名，不含扩展名）：单段、无路径分隔符。中文段必需——
     * 现有目录型智能体的子智能体名就是中文（如「行程规划子智能体」）。
     */
    private static final Pattern SUBAGENT_BARE_NAME =
            Pattern.compile("^[\\w\\-\\u4e00-\\u9fa5]{1,96}$");

    /**
     * 子智能体文件名白名单：单段、{@code .md} 结尾，防路径穿越。与目录型在线编辑
     * （{@code AgentConfigService.FILE_SUBAGENT_NAME}）同一口径，两侧落出的文件名
     * 才是同一套。
     */
    private static final Pattern SUBAGENT_FILE_NAME =
            Pattern.compile("^[\\w\\-\\u4e00-\\u9fa5]{1,96}\\.md$");

    private final Path workspaceRoot;

    public WorkspaceGenerator(@Value("${agent-platform.workspace-root}") String workspaceRoot) {
        this.workspaceRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize();
    }

    /**
     * 生成（或重建）Agent 工作区，返回相对 workspaceRoot 的路径（如 agents/{id}）。
     * 只重建平台管理的文件，不触碰运行态产物（runtimes/ 与模型残留文件），
     * 也不覆盖用户在定义侧手写的内容。
     */
    public String generate(AgentConfig config, List<Skill> skills, List<Tool> tools) {
        return generate(config, skills, tools, List.of(), null);
    }

    public String generate(AgentConfig config, List<Skill> skills, List<Tool> tools,
                           List<Subagent> subagents) {
        return generate(config, skills, tools, subagents, null);
    }

    /**
     * 生成（或重建）智能体目录，并落盘子智能体。
     *
     * <p>{@code inline_subagents} 列已删，子智能体由调用方传入：配置页保存时来自请求体，
     * 迁移回填时来自待迁移数据。
     *
     * @param config    智能体配置
     * @param skills    已勾选技能
     * @param tools     已勾选工具
     * @param subagents 子智能体清单；为空表示<b>不动</b> subagents/（保留定义侧现状）
     * @param persona   建��时表单填的人设正文；只在 AGENTS.md 尚不存在时写入骨架，
     *                 为 null 表示不提供（此时生成不含人设正文的最小骨架）
     * @return 相对 workspaceRoot 的智能体目录路径
     */
    public String generate(AgentConfig config, List<Skill> skills, List<Tool> tools,
                           List<Subagent> subagents, String persona) {
        // 尊重已登记的 workspace_path；未登记时按 id 命名。
        // 存量两类命名并存：智能体配置是 agents/{uuid}，智能体目录来源的是 agents/{目录名}
        // （中文名，目录名决定 id ⇒ 不能改成 uuid，否则 id 会漂移、历史会话失联）。
        // 这里若一律用 id 命名，迁移回填就会写到与实际智能体目录不同的目录里，
        // 表现为「回填后磁盘仍是 0 个文件」而校验查不出来。
        String registered = config.getWorkspacePath();
        Path agentDir = (registered == null || registered.isBlank()
                ? workspaceRoot.resolve("agents").resolve(config.getId().toString())
                : workspaceRoot.resolve(registered)).normalize();
        if (!agentDir.startsWith(workspaceRoot)) {
            throw ApiException.badRequest("非法工作区路径");
        }
        try {
            // 精确清理平台生成且可完整重建的目录（不含 subagents/ 与 AGENTS.md：
            // 二者都是 agent 定义的组成部分，由用户在定义侧编辑）。
            // 其他文件（模型残留等）保留并告警
            deleteDefinitionFiles(agentDir);
            Files.createDirectories(agentDir.resolve("skills"));

            // AGENTS.md 是人格与行为约定的唯一载体（官方约定：AGENTS.md 承载
            // persona / behavior rules / system instructions）。平台只在它不存在时
            // 写一次初始骨架；此后一律不碰——用户在定义侧写的内容不会被表单保存覆盖。
            if (!Files.exists(agentDir.resolve("AGENTS.md"))) {
                Files.writeString(agentDir.resolve("AGENTS.md"),
                        renderInitialAgentsMd(config, persona), StandardCharsets.UTF_8);
            }
            for (Skill skill : skills) {
                Path target = agentDir.resolve("skills").resolve(skill.getName() + "/SKILL.md");
                Files.createDirectories(target.getParent());
                Files.writeString(target, renderSkill(skill), StandardCharsets.UTF_8);
                writeSkillResources(agentDir.resolve("skills").resolve(skill.getName()), skill);
            }
            syncSubagentSpecs(agentDir, subagents);
        } catch (IOException e) {
            log.error("Failed to generate workspace for agent {}", config.getId(), e);
            throw ApiException.badRequest("工作区生成失败: " + e.getMessage());
        }

        String relative = workspaceRoot.relativize(agentDir).toString();
        config.setWorkspacePath(relative);
        return relative;
    }

    /**
     * 只删除智能体目录中平台生成且可完整重建的目录；目录中存在其他内容（模型残留、
     * 非平台文件）时保留并记录告警，避免误删。
     *
     * <p><b>subagents/ 与 AGENTS.md 均不在其中</b>：
     * <ul>
     *   <li>{@code subagents/} 是子智能体定义，由本生成器写入、由用户在定义侧编辑；
     *       删掉它等于抹掉用户在 AGENTS.md 之外的手工编排。</li>
     *   <li>{@code AGENTS.md} 同理——它是人格与行为约定的唯一载体，平台只在文件不存在时
     *       写一次初始骨架（见 {@link #renderInitialAgentsMd}），此后完全不碰。</li>
     * </ul>
     */
    private void deleteDefinitionFiles(Path agentDir) throws IOException {
        if (!Files.exists(agentDir)) {
            return;
        }
        deleteIfExistsRecursively(agentDir.resolve("skills"));
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

    /**
     * 新建智能体时的 AGENTS.md 初始骨架。
     *
     * <p>按 AgentScope 官方约定，AGENTS.md 承载 persona / behavior rules /
     * system instructions，是<b>人格与行为约定的唯一载体</b>（原文：Only AGENTS.md
     * is something you actually need to write）。因此这里只写标题、描述与人设正文，
     * <b>不再生成技能 / 工具 / 子 Agent 三段清单</b>——它们各有官方加载机制：
     * 技能由技能中间件每轮渲染 {@code <available_skills>}，子 Agent 由
     * {@code AgentSpecLoader} 在 build 时扫描 {@code subagents/*.md}，
     * 工具与 MCP 归 {@code tools.json}。在 AGENTS.md 里再抄一份只会让模型看到
     * 两份内容，且平台勾选与文件内容必然漂移。
     *
     * <p>本方法只在 AGENTS.md 尚不存在时调用；已存在则完全不碰——
     * 用户在定义侧写的内容不会被表单保存覆盖。
     *
     * @param config  智能体配置（取名称与描述）
     * @param persona 新建时表单填的人设正文；为空则生成不含正文段的最小骨架
     */
    private static String renderInitialAgentsMd(AgentConfig config, String persona) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(config.getName()).append("\n\n");
        if (config.getDescription() != null && !config.getDescription().isBlank()) {
            sb.append(config.getDescription().strip()).append("\n\n");
        }
        if (persona != null && !persona.isBlank()) {
            sb.append(persona.strip()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 把 {@code inline_subagents} 落盘为 {@code subagents/{name}.md}。
     *
     * <p>框架契约：文件名（去掉 {@code .md}）即 {@code agent_id}，frontmatter 不写
     * {@code name}；{@code description} 必填（父 Agent 据此决定是否派发）；
     * {@code tools} 是工具白名单（缺失等于放开全部工具，属权限面变宽，必须原样写入）；
     * {@code steps} 为单轮迭代上限；{@code workspace.mode: shared} 让子 Agent 与父共享
     * 工作区，使其能触发平台 UI 工具。
     *
     * <p>只写平台管理的文件，不删除用户在定义侧新增的子智能体。
     */

    /**
     * 子智能体落盘。
     *
     * <p>{@code inline_subagents} 列已删，因此子智能体不再来自 AgentConfig，而是<b>由调用方
     * 传入</b>——配置页保存时来自请求体，迁移回填时来自待迁移数据。
     *
     * <p>只写平台管理的文件，不删除用户在定义侧新增的子智能体。
     *
     * @param agentDir   智能体目录
     * @param subagents  待落盘的子智能体（可为 null / 空）
     * @throws IOException 写盘失败
     */
    public void syncSubagentSpecs(Path agentDir, List<Subagent> subagents) throws IOException {
        List<Subagent> targets = subagents == null ? List.of() : subagents;
        if (targets.isEmpty()) {
            return;
        }
        Path dir = agentDir.resolve("subagents");
        Files.createDirectories(dir);
        for (Subagent subagent : subagents) {
            if (subagent.name() == null || subagent.name().isBlank()) {
                continue;
            }
            String fileName = toSubagentFileName(subagent.name());
            Files.writeString(dir.resolve(fileName), renderSubagent(subagent), StandardCharsets.UTF_8);
        }
    }

    /**
     * 创建流程专用：把用户当场新建/上传的子智能体正文写成 {@code subagents/{name}.md}。
     *
     * <p>与 {@link #syncSubagentSpecs} 的区别：那里吃结构化 {@link Subagent} 再渲染
     * frontmatter；这里吃「裸名 → 正文」的成品，直接落盘（新建 .md / 上传 .md 已是完整
     * 文件内容）。文件名仍走 {@link #toSubagentFileName} 的同一校验口径，避免路径穿越与
     * 两侧文件名分裂。{@code deleteDefinitionFiles} 本就不清 subagents/，故创建后调用安全。
     */
    public void writeSubagentContent(Path agentDir, Map<String, String> subagentFiles) throws IOException {
        if (subagentFiles == null || subagentFiles.isEmpty()) {
            return;
        }
        Path dir = agentDir.resolve("subagents");
        Files.createDirectories(dir);
        for (Map.Entry<String, String> entry : subagentFiles.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            String fileName = toSubagentFileName(entry.getKey());
            Files.writeString(dir.resolve(fileName), entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 校验子智能体名称并拼出文件名。
     *
     * <p>校验的是**裸名**（配置里存的是名称，不含扩展名），因此用不含 {@code \.md} 的
     * 表达式；拼上扩展名后仍要满足目录型编辑侧的 {@code FILE_SUBAGENT_NAME} 口径，
     * 两侧落出的文件名才是同一套。
     */
    private String toSubagentFileName(String name) {
        String trimmed = name.trim();
        String fileName = trimmed + ".md";
        if (!SUBAGENT_BARE_NAME.matcher(trimmed).matches() || !SUBAGENT_FILE_NAME.matcher(fileName).matches()) {
            throw ApiException.badRequest("子智能体名称不可用作文件名: " + name);
        }
        return fileName;
    }

    private String renderSubagent(Subagent subagent) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("description: ")
                .append(subagent.description() == null || subagent.description().isBlank()
                        ? subagent.name() : singleLine(subagent.description()))
                .append("\n");
        sb.append("mode: subagent\n");
        sb.append("workspace:\n  mode: shared\n");
        if (subagent.steps() != null && subagent.steps() > 0) {
            sb.append("steps: ").append(subagent.steps()).append("\n");
        }
        if (subagent.model() != null && !subagent.model().isBlank()) {
            sb.append("model: ").append(singleLine(subagent.model())).append("\n");
        }
        if (subagent.tools() != null && !subagent.tools().isEmpty()) {
            sb.append("tools: [").append(String.join(", ", subagent.tools())).append("]\n");
        }
        sb.append("---\n\n");
        sb.append("# ").append(subagent.name()).append("\n\n");
        sb.append(subagent.prompt() == null ? "" : subagent.prompt()).append("\n");
        return sb.toString();
    }

    /** YAML 标量值不能含换行，否则解析错位；折叠为单行。 */
    private String singleLine(String value) {
        return value.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    /**
     * 渲染工作区层的技能定义（{@code skills/<name>/SKILL.md}）。
     *
     * <p>工作区层是 skill 四层来源之一（低于用户隔离层、高于市场层），同名会覆盖平台
     * 技能库版本——因此这里只落平台技能库的**快照**，让智能体目录自包含可移植。
     */
    private String renderSkill(Skill skill) {
        return "---\n"
                + "name: " + skill.getName() + "\n"
                + "description: " + (skill.getDescription() == null ? "" : singleLine(skill.getDescription())) + "\n"
                + "---\n\n"
                + "# " + skill.getName() + "\n\n"
                + (skill.getContent() == null || skill.getContent().isBlank()
                        ? (skill.getDescription() == null ? "" : skill.getDescription())
                        : skill.getContent())
                + "\n";
    }

    /** 落盘技能的附属文本资源（references / scripts 等），路径按 skills 约定相对定位。 */
    private void writeSkillResources(Path skillDir, Skill skill) throws IOException {
        Map<String, String> resources = skill.getResources();
        if (resources == null || resources.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            String rel = entry.getKey();
            if (rel == null || rel.isBlank()) {
                continue;
            }
            Path target = skillDir.resolve(rel).normalize();
            if (!target.startsWith(skillDir)) {
                throw ApiException.badRequest("非法技能资源路径: " + rel);
            }
            if (target.equals(skillDir)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue() == null ? "" : entry.getValue(),
                    StandardCharsets.UTF_8);
        }
    }

}
