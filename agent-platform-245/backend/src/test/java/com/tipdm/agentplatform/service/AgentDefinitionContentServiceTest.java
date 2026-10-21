package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.Tool;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.repository.ToolRepository;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 定义内容的增删：私有技能目录与工具启用项。
 *
 * <p>要点：
 * <ul>
 *   <li>私有技能只落在本智能体的 {@code skills/} 下，不进平台技能库、不与其他智能体共享；</li>
 *   <li>同名不覆盖（已有技能可能是用户手写的，静默覆盖等于丢数据）；</li>
 *   <li>删除前查引用——被 {@code subagents/*.md} 的 tools frontmatter 引用时拒绝，
 *       因为那些子智能体会在运行时因缺少技能/工具而失败。</li>
 * </ul>
 */
class AgentDefinitionContentServiceTest {

    @TempDir
    Path workspaceRoot;

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);
    private final ToolRepository toolRepository = mock(ToolRepository.class);
    private final SkillService skillService = mock(SkillService.class);

    private AgentDefinitionContentService newService() {
        return new AgentDefinitionContentService(workspaceRoot.toString(), repository, toolRepository, skillService);
    }

    private AgentConfig givenAgentWithWorkspacePath(String workspacePath) {
        AgentConfig config = new AgentConfig();
        config.setId(UUID.randomUUID());
        config.setName("测试智能体");
        config.setWorkspacePath(workspacePath);
        config.setToolIds(new ArrayList<>());
        when(repository.findById(config.getId())).thenReturn(Optional.of(config));
        return config;
    }

    private byte[] zipOf(String rootDir, String... files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String f : files) {
                zip.putNextEntry(new ZipEntry(rootDir == null ? f : rootDir + "/" + f));
                zip.write("# 内容".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ==================== 上传技能 ====================

    @Test
    void uploadSkillLandsInAgentSkillsDirectory() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");

        AgentDefinitionContentService.SkillUploadResult result = newService()
                .uploadSkillFromZip(config.getId(),
                        new java.io.ByteArrayInputStream(zipOf("jc-review", "SKILL.md")));

        assertEquals("jc-review", result.skillName());
        Path skillMd = workspaceRoot.resolve("agents/测试智能体/skills/jc-review/SKILL.md");
        assertTrue(Files.exists(skillMd), "技能须落在本智能体的 skills/{name}/ 下");
        assertEquals(1, result.fileCount());
    }

    @Test
    void uploadSkillRejectsPackageWithoutSkillMd() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");

        ApiException e = assertThrows(ApiException.class, () -> newService()
                .uploadSkillFromZip(config.getId(),
                        new java.io.ByteArrayInputStream(zipOf("bad-skill", "README.md"))));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus(), "缺 SKILL.md 应为 400");
        assertFalse(Files.exists(workspaceRoot.resolve("agents/测试智能体/skills/bad-skill")),
                "校验不通过时不得留下任何文件");
    }

    @Test
    void uploadSkillRejectsSameName() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Path existing = workspaceRoot.resolve("agents/测试智能体/skills/jc-review");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("SKILL.md"), "手写的", StandardCharsets.UTF_8);

        ApiException e = assertThrows(ApiException.class, () -> newService()
                .uploadSkillFromZip(config.getId(),
                        new java.io.ByteArrayInputStream(zipOf("jc-review", "SKILL.md"))));

        assertEquals(HttpStatus.CONFLICT, e.getStatus(), "同名应为 409");
        assertEquals("手写的",
                Files.readString(existing.resolve("SKILL.md"), StandardCharsets.UTF_8),
                "同名时不得覆盖已有技能内容");
    }

    @Test
    void uploadSkillStripsWrapperDirectory() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");

        AgentDefinitionContentService.SkillUploadResult result = newService()
                .uploadSkillFromZip(config.getId(),
                        new java.io.ByteArrayInputStream(zipOf("package-root/jc-2-数据准备", "SKILL.md")));

        assertEquals("jc-2-数据准备", result.skillName(),
                "多包一层时应剥掉包装层，取真正含 SKILL.md 的那一层作技能名");
        assertTrue(Files.exists(
                workspaceRoot.resolve("agents/测试智能体/skills/jc-2-数据准备/SKILL.md")));
    }

    @Test
    void uploadSkillRejectsPathTraversalEntry() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");

        assertThrows(ApiException.class, () -> newService()
                .uploadSkillFromZip(config.getId(),
                        new java.io.ByteArrayInputStream(zipOf("evil", "../escape.txt"))));
    }

    // ==================== 从技能库复制 ====================

    @Test
    void copySkillFromLibraryWritesManifestAndResources() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Skill skill = new Skill();
        skill.setName("lib-skill");
        skill.setContent("## 技能正文");
        skill.setResources(java.util.Map.of("scripts/run.py", "print('ok')"));
        when(skillService.readSkillBody(skill)).thenReturn("## 技能正文");

        AgentDefinitionContentService.SkillUploadResult result = newService()
                .copySkillFromLibrary(config.getId(), skill);

        assertEquals("lib-skill", result.skillName());
        assertEquals(2, result.fileCount());
        Path skillDir = workspaceRoot.resolve("agents/测试智能体/skills/lib-skill");
        assertEquals("## 技能正文",
                Files.readString(skillDir.resolve("SKILL.md"), StandardCharsets.UTF_8));
        assertEquals("print('ok')",
                Files.readString(skillDir.resolve("scripts/run.py"), StandardCharsets.UTF_8));
    }

    @Test
    void copySkillFromLibraryRejectsSameName() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Path existing = workspaceRoot.resolve("agents/测试智能体/skills/lib-skill");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("SKILL.md"), "手写", StandardCharsets.UTF_8);
        Skill skill = new Skill();
        skill.setName("lib-skill");
        skill.setContent("## 来自库");
        when(skillService.readSkillBody(skill)).thenReturn("## 来自库");

        ApiException e = assertThrows(ApiException.class,
                () -> newService().copySkillFromLibrary(config.getId(), skill));

        assertEquals(HttpStatus.CONFLICT, e.getStatus(), "同名应为 409");
        assertEquals("手写",
                Files.readString(existing.resolve("SKILL.md"), StandardCharsets.UTF_8),
                "同名时不得覆盖已有技能内容");
    }

    @Test
    void copySkillFromLibraryRejectsBlankBody() {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Skill skill = new Skill();
        skill.setName("empty-skill");
        when(skillService.readSkillBody(skill)).thenReturn("");

        ApiException e = assertThrows(ApiException.class,
                () -> newService().copySkillFromLibrary(config.getId(), skill));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    // ==================== 移除技能 ====================

    @Test
    void removeSkillDeletesWholeDirectory() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Path skillDir = workspaceRoot.resolve("agents/测试智能体/skills/jc-review");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("SKILL.md"), "x", StandardCharsets.UTF_8);
        Files.writeString(skillDir.resolve("scripts/run.py"), "y", StandardCharsets.UTF_8);

        newService().removeSkill(config.getId(), "jc-review");

        assertFalse(Files.exists(skillDir), "移除技能须删掉整个目录（含 scripts/）");
    }

    @Test
    void removeSkillRejectedWhenReferencedBySubagent() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Path skillDir = workspaceRoot.resolve("agents/测试智能体/skills/jc-2-数据准备");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "x", StandardCharsets.UTF_8);
        Path spec = workspaceRoot.resolve("agents/测试智能体/subagents/资料准备智能体.md");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "---\ntools: [jc-2-数据准备, read_file]\n---\n\n正文",
                StandardCharsets.UTF_8);

        ApiException e = assertThrows(ApiException.class,
                () -> newService().removeSkill(config.getId(), "jc-2-数据准备"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus(), "被引用时应为 409");
        assertTrue(e.getMessage().contains("资料准备智能体"),
                "错误信息须带出引用者，供用户判断");
        assertTrue(Files.exists(skillDir), "被拒绝时不得删除技能目录");
    }

    @Test
    void removeSkillIgnoresMentionOutsideToolsFrontmatter() throws IOException {
        // 只扫 frontmatter 的 tools 列表：正文里提到工具名不算引用
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Path skillDir = workspaceRoot.resolve("agents/测试智能体/skills/jc-2-数据准备");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "x", StandardCharsets.UTF_8);
        Path spec = workspaceRoot.resolve("agents/测试智能体/subagents/正文提及.md");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "---\ntools: [read_file]\n---\n\n本智能体会调用 jc-2-数据准备。",
                StandardCharsets.UTF_8);

        newService().removeSkill(config.getId(), "jc-2-数据准备");

        assertFalse(Files.exists(skillDir), "正文提及不构成引用，应允许移除");
    }

    /**
     * 带空格的工具名在 YAML 里必须加引号，引用检查必须能识别。
     *
     * <p>缺陷形态：{@code tools: ["Web 搜索"]} 的列表项带引号，与工具名
     * {@code Web 搜索} 比对失败 ⇒ 引用检查对它<b>完全失效</b>，
     * 而带空格的名字恰是最常被写成引号形式的那批。端到端实测发现。
     */
    @Test
    void disableToolDetectsQuotedNameContainingSpace() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Tool tool = new Tool();
        tool.setId(UUID.randomUUID());
        tool.setName("Web 搜索");
        tool.setToolType("mcp");
        config.setToolIds(List.of(tool.getId()));
        when(toolRepository.findAll()).thenReturn(List.of(tool));
        Path spec = workspaceRoot.resolve("agents/测试智能体/subagents/检索子智能体.md");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "---\ntools: [\"Web 搜索\"]\n---\n\n正文",
                StandardCharsets.UTF_8);

        ApiException e = assertThrows(ApiException.class,
                () -> newService().disableTool(config.getId(), "Web 搜索"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus(),
                "引号包裹的带空格工具名仍应被识别为引用");
    }

    /** 单引号形式同样要能识别（YAML 两种引号等价）。 */
    @Test
    void disableToolDetectsSingleQuotedName() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Tool tool = new Tool();
        tool.setId(UUID.randomUUID());
        tool.setName("Web 搜索");
        tool.setToolType("mcp");
        config.setToolIds(List.of(tool.getId()));
        when(toolRepository.findAll()).thenReturn(List.of(tool));
        Path spec = workspaceRoot.resolve("agents/测试智能体/subagents/检索子智能体.md");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "---\ntools: ['Web 搜索']\n---\n\n正文",
                StandardCharsets.UTF_8);

        ApiException e = assertThrows(ApiException.class,
                () -> newService().disableTool(config.getId(), "Web 搜索"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void removeSkillRejectsIllegalName() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");

        assertThrows(ApiException.class, () -> newService().removeSkill(config.getId(), "../escape"));
    }

    @Test
    void listSkillNamesReturnsDirectoriesSorted() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Path skills = workspaceRoot.resolve("agents/测试智能体/skills");
        Files.createDirectories(skills.resolve("jc-3"));
        Files.createDirectories(skills.resolve("jc-1"));
        Files.createDirectories(skills.resolve("_shared"));
        Files.writeString(skills.resolve("loose.md"), "散落文件", StandardCharsets.UTF_8);

        List<String> names = newService().listSkillNames(config.getId());

        assertEquals(List.of("_shared", "jc-1", "jc-3"), names);
        assertFalse(names.contains("loose.md"), "散落在 skills/ 下的文件不是技能目录");
    }

    // ==================== 工具启用 ====================

    @Test
    void enableToolAppendsToToolIds() {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Tool tool = new Tool();
        tool.setId(UUID.randomUUID());
        tool.setName("ui_gate");
        tool.setToolType("platform");
        when(toolRepository.findAll()).thenReturn(List.of(tool));
        when(toolRepository.findAllById(List.of(tool.getId()))).thenReturn(List.of(tool));

        List<String> enabled = newService().enableTool(config.getId(), "ui_gate");

        assertEquals(List.of("ui_gate"), enabled);
        assertEquals(List.of(tool.getId()), config.getToolIds());
        Mockito.verify(repository).save(config);
    }

    @Test
    void enableToolRejectsUnknownTool() {
        givenAgentWithWorkspacePath("agents/测试智能体");
        when(toolRepository.findAll()).thenReturn(List.of());

        assertThrows(ApiException.class, () -> newService().enableTool(UUID.randomUUID(), "nope"));
    }

    /**
     * 停用一个平台工具库里不存在的名字必须报 404。
     *
     * <p>缺陷形态：此前用 {@code ifPresent}，名字不存在时静默跳过并仍返 200，
     * 前端据此显示「已停用」——但配置从未被读过，状态不明被「成功」掩盖。
     */
    @Test
    void disableToolRejectsUnknownTool() {
        givenAgentWithWorkspacePath("agents/测试智能体");
        when(toolRepository.findAll()).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class,
                () -> newService().disableTool(UUID.randomUUID(), "nope"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }


    @Test
    void disableToolRejectedWhenReferencedBySubagent() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Tool tool = new Tool();
        tool.setId(UUID.randomUUID());
        tool.setName("agent_spawn");
        tool.setToolType("platform");
        config.setToolIds(List.of(tool.getId()));
        when(toolRepository.findAll()).thenReturn(List.of(tool));
        Path spec = workspaceRoot.resolve("agents/测试智能体/subagents/行程规划子智能体.md");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "---\ntools: [agent_spawn, read_file]\n---\n\n正文",
                StandardCharsets.UTF_8);

        ApiException e = assertThrows(ApiException.class,
                () -> newService().disableTool(config.getId(), "agent_spawn"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("行程规划子智能体"));
        assertEquals(List.of(tool.getId()), config.getToolIds(), "被拒绝时不得改动 tool_ids");
    }

    @Test
    void disableToolRemovesFromToolIds() throws IOException {
        AgentConfig config = givenAgentWithWorkspacePath("agents/测试智能体");
        Tool tool = new Tool();
        tool.setId(UUID.randomUUID());
        tool.setName("read_file");
        tool.setToolType("platform");
        config.setToolIds(new ArrayList<>(List.of(tool.getId())));
        when(toolRepository.findAll()).thenReturn(List.of(tool));

        List<String> enabled = newService().disableTool(config.getId(), "read_file");

        assertTrue(enabled.isEmpty());
        assertTrue(config.getToolIds().isEmpty());
    }

}
