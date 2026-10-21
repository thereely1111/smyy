package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.Subagent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体目录生成器的落盘契约。
 *
 * <p>要点：AGENTS.md、skills/&lt;name&gt;/SKILL.md、subagents/&lt;id&gt;.md 三类内容是框架按目录
 * 约定的自动发现依据（{@code builder.workspace()} 指向智能体目录），因此落盘形态即框架契约：
 * 子智能体文件名即 agent_id、frontmatter 不写 name、description 必填、tools 白名单原样
 * 保留。
 */
class WorkspaceGeneratorTest {

    @TempDir
    Path workspaceRoot;

    private WorkspaceGenerator newGenerator() {
        return new WorkspaceGenerator(workspaceRoot.toString());
    }

    private AgentConfig newConfig() {
        AgentConfig config = new AgentConfig();
        config.setId(UUID.randomUUID());
        config.setName("测试智能体");
        config.setSkillIds(List.of());
        config.setToolIds(List.of());
        return config;
    }

    private Path agentDir(AgentConfig config) {
        return workspaceRoot.resolve("agents").resolve(config.getId().toString());
    }

    @Test
    void generate_writesAgentsMdWithPersona() throws IOException {
        AgentConfig config = newConfig();
        String rel = newGenerator()
                .generate(config, List.of(), List.of(), List.of(), "你是测试助手。");

        assertEquals("agents/" + config.getId(), rel);
        String md = Files.readString(agentDir(config).resolve("AGENTS.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("# 测试智能体"));
        assertTrue(md.contains("你是测试助手。"), "新建时填的人设必须写入 AGENTS.md");
    }

    @Test
    void generate_omitsPersonaSectionWhenBlank() throws IOException {
        // 人设留空时只写标题与描述，不产出空段落——空正文进了 prompt 是纯噪音
        AgentConfig config = newConfig();

        newGenerator().generate(config, List.of(), List.of(), List.of(), "   ");

        String md = Files.readString(agentDir(config).resolve("AGENTS.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("# 测试智能体"));
        assertFalse(md.contains("##"), "无内容时不应留下空标题：\n" + md);
    }

    @Test
    void generate_leavesExistingAgentsMdUntouched() throws IOException {
        // 核心口径：AGENTS.md 是人格与行为约定的唯一载体，平台只在文件不存在时写一次。
        // 再次保存配置（哪怕带了新的 persona）也必须一字不改——
        // 否则用户在定义侧写的内容会被表单保存静默覆盖。
        AgentConfig config = newConfig();
        WorkspaceGenerator generator = newGenerator();
        Path agentsMd = agentDir(config).resolve("AGENTS.md");
        Files.createDirectories(agentsMd.getParent());
        String handWritten = "# 旅行规划师\n\n## 硬约束\n\n1. 必须先确认预算\n";
        Files.writeString(agentsMd, handWritten, StandardCharsets.UTF_8);

        generator.generate(config, List.of(), List.of(), List.of(), "这次传的人设不该生效");

        assertEquals(handWritten, Files.readString(agentsMd, StandardCharsets.UTF_8),
                "AGENTS.md 已存在时平台不得改写——用户在定义侧写的内容优先");
    }

    @Test
    void generate_writesSubagentSpecsWithFullDefinition() throws IOException {
        AgentConfig config = newConfig();
        List<Subagent> subagents = List.of(
                new Subagent("行程规划", "生成行程草案。", "你是行程规划子智能体。",
                        null, List.of("read_file", "write_file"), 20));

        newGenerator().generate(config, List.of(), List.of(), subagents);

        Path spec = agentDir(config).resolve("subagents").resolve("行程规划.md");
        assertTrue(Files.exists(spec), "子智能体必须落盘为 subagents/{name}.md");
        String text = Files.readString(spec, StandardCharsets.UTF_8);
        // description 是父 agent 决定是否派发的唯一依据
        assertTrue(text.contains("description: 生成行程草案。"));
        // prompt 是子 agent 的 system prompt，丢失即变成空壳
        assertTrue(text.contains("你是行程规划子智能体。"), "prompt 正文必须落盘");
        // tools 是工具白名单，丢失等于放开全部工具
        assertTrue(text.contains("tools: [read_file, write_file]"), "工具白名单必须原样保留");
        assertTrue(text.contains("steps: 20"));
        assertTrue(text.contains("mode: subagent"));
        assertTrue(text.contains("mode: shared"), "子智能体需与父共享工作区才能触发平台 UI 工具");
    }

    @Test
    void generate_doesNotWriteNameInSubagentFrontmatter() throws IOException {
        // 框架契约：文件名即 agent_id，frontmatter 再写 name 会造成歧义
        AgentConfig config = newConfig();
        List<Subagent> subagents = List.of(
                new Subagent("审核员", "负责审核。", "你是审核员。", null, List.of(), null));

        newGenerator().generate(config, List.of(), List.of(), subagents);

        String text = Files.readString(agentDir(config).resolve("subagents").resolve("审核员.md"),
                StandardCharsets.UTF_8);
        String frontmatter = text.substring(0, text.indexOf("---", 3));
        assertFalse(frontmatter.contains("name:"), "frontmatter 不应出现 name 键");
    }

    @Test
    void generate_keepsUserAuthoredSubagentFiles() throws IOException {
        // subagents/ 属智能体目录内容，不归平台清理：重新生成不得抹掉手工新增的子智能体
        AgentConfig config = newConfig();
        List<Subagent> subagents = List.of(
                new Subagent("现配", "现配子智能体。", "正文 A。", null, List.of(), null));
        WorkspaceGenerator generator = newGenerator();
        generator.generate(config, List.of(), List.of(), subagents);

        Path manual = agentDir(config).resolve("subagents").resolve("手工新增.md");
        Files.writeString(manual, "---\ndescription: 手工写的。\n---\n\n正文 B。", StandardCharsets.UTF_8);

        // 第二次生成：清单变化为另一个子智能体
        List<Subagent> secondBatch = List.of(
                new Subagent("另一个", "另一个子智能体。", "正文 C。", null, List.of(), null));
        generator.generate(config, List.of(), List.of(), secondBatch);

        assertTrue(Files.exists(manual), "用户在定义侧新增的子智能体不得被删除");
        assertTrue(Files.exists(agentDir(config).resolve("subagents").resolve("另一个.md")));
    }

    @Test
    void generate_rejectsPathTraversalSubagentName() {
        AgentConfig config = newConfig();
        List<Subagent> subagents = List.of(
                new Subagent("../escape", "越界名。", "正文。", null, List.of(), null));

        // ⚠️ 必须把 subagents 传进去：传空列表时校验分支根本走不到，测试会假绿
        assertThrows(com.tipdm.agentplatform.web.error.ApiException.class,
                () -> newGenerator().generate(config, List.of(), List.of(), subagents));
    }

    @Test
    void generate_writesSkillAsWorkspaceLayerDirectory() throws IOException {
        AgentConfig config = newConfig();
        Skill skill = new Skill();
        skill.setId(UUID.randomUUID());
        skill.setName("code-reviewer");
        skill.setDescription("当需要代码评审时使用。");
        skill.setContent("# 步骤\n1. 读代码\n");
        skill.setResources(Map.of("references/style.md", "风格规范正文"));

        newGenerator().generate(config, List.of(skill), List.of());

        Path skillMd = agentDir(config).resolve("skills").resolve("code-reviewer").resolve("SKILL.md");
        assertTrue(Files.exists(skillMd), "技能须落在 skills/<name>/SKILL.md（框架工作区层约定）");
        String text = Files.readString(skillMd, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("---"), "SKILL.md 以 YAML frontmatter 开头");
        assertTrue(text.contains("name: code-reviewer"));
        assertTrue(text.contains("当需要代码评审时使用。"));
        assertTrue(text.contains("1. 读代码"), "技能正文必须落盘，不能只有名称与描述");
        assertTrue(Files.exists(
                        agentDir(config).resolve("skills").resolve("code-reviewer")
                                .resolve("references").resolve("style.md")),
                "技能附属资源须落盘");
    }

    @Test
    void generate_keepsSubagentOutOfAgentsMd() throws IOException {
        // 子 Agent 由 AgentSpecLoader 在 build 时扫描 subagents/*.md 自动发现，
        // AGENTS.md 里不再维护「子 Agent」索引段：索引与实际文件必然漂移，
        // 而框架本来就不读它。定义只应落在 subagents/ 下。
        AgentConfig config = newConfig();
        List<Subagent> subagents = List.of(
                new Subagent("审核员", "负责审核。", "你是审核员。", null, List.of(), null));

        newGenerator().generate(config, List.of(), List.of(), subagents);

        Path spec = agentDir(config).resolve("subagents").resolve("审核员.md");
        assertTrue(Files.exists(spec), "子智能体定义应落在 subagents/ 下由框架自动发现");
        assertTrue(Files.readString(spec, StandardCharsets.UTF_8).contains("你是审核员。"),
                "子智能体的系统提示词应在它自己的文件里");
        String md = Files.readString(agentDir(config).resolve("AGENTS.md"), StandardCharsets.UTF_8);
        assertFalse(md.contains("审核员"), "AGENTS.md 不该再维护子 Agent 索引");
        assertFalse(md.contains("你是审核员。"), "子智能体 prompt 不应在 AGENTS.md 重复");
    }

    /** 一份用户手写过的 AGENTS.md：形态取自真实的编排型智能体。 */
    private static final String HAND_WRITTEN_AGENTS_MD = """
            ---
            tags: [生活助手]
            ---

            # 旅行规划师

            演示编排 + 子智能体 + 审批闸门的多智能体形态。

            > **权限规则**：写入仅限 artifacts/ 目录；execute 仅限 ls / pwd 诊断。

            ## 硬约束

            1. **HITL 统一通道**：凡需用户确认，必须调用 ui_gate 渲染结构化决策表单。
            2. **闸门协议**：进入闸门时先输出产物路径，随即调用 ui_gate 并结束本轮。

            ## 工作流程

            IDLE -> 收到旅行请求 -> ui_form_advanced 收集出行意向
            """;

    @Test
    void generate_neverTouchesAgentsMdAfterCreation() throws IOException {
        // 缺陷形态：AGENTS.md 是人格与行为约定的唯一载体（官方约定），用户手写的硬约束、
        // 工作流程、闸门协议只存在于该文件里。此前平台保存配置时会重写其中的
        // 「系统提示词/技能/工具/子 Agent」四段；用户随后在定义侧改了文件里的其它内容，
        // 再点一次保存就会被旧值覆盖。⇒ 现在的口径是：文件一旦存在，平台一律不碰。
        AgentConfig config = newConfig();
        WorkspaceGenerator generator = newGenerator();
        Path agentsMd = agentDir(config).resolve("AGENTS.md");
        Files.createDirectories(agentsMd.getParent());
        Files.writeString(agentsMd, HAND_WRITTEN_AGENTS_MD, StandardCharsets.UTF_8);

        // 模拟「用户在定义侧改完文件后，又点了一次保存配置」
        String edited = HAND_WRITTEN_AGENTS_MD + "\n## 补充\n\n用户后加的一节。\n";
        Files.writeString(agentsMd, edited, StandardCharsets.UTF_8);
        generator.generate(config, List.of(), List.of(), List.of(), "配置侧又传了个人设");

        assertEquals(edited, Files.readString(agentsMd, StandardCharsets.UTF_8),
                "保存配置不得改写已存在的 AGENTS.md——连用户在定义侧新加的段落也要保住");
        String md = Files.readString(agentsMd, StandardCharsets.UTF_8);
        assertTrue(md.contains("HITL 统一通道"), "手写的硬约束不得消失");
        assertTrue(md.contains("用户后加的一节。"), "定义侧新增的段落不得消失");
        assertFalse(md.contains("配置侧又传了个人设"),
                "配置侧不再有权往已存在的 AGENTS.md 里写人设");
    }
}
