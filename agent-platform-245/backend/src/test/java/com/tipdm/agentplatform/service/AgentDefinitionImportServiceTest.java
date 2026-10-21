package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.util.AgentDirId;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体智能体目录导入服务。
 *
 * <p><b>安全边界是本类的首要测试目标</b>：这是本项目唯一的新增解压入口，此前全库只有
 * 打包方向（{@code ArtifactZipService}），无可复用的 zip slip 防护。zip slip 一旦漏防，
 * 攻击者可用 {@code ../} 写出暂存目录之外的任意文件——包括覆盖 agent 定义与配置。
 *
 * <p>第二个重点是<b>根目录 AGENTS.md 必需</b>：缺失时产出的目录无法被框架识别，
 * 管理端也拿不到可用对象，属静默失效。
 */
class AgentDefinitionImportServiceTest {

    @TempDir
    Path workspaceRoot;

    private AgentDefinitionImportService newService() {
        return new AgentDefinitionImportService(workspaceRoot.toString());
    }

    // ======================== 正常路径 ========================

    @Test
    void importFromZip_writesDefinitionWithSubagentsAndSkills() throws IOException {
        byte[] zip = zip(
                entry("AGENTS.md", "# 旅行规划师\n\n你负责编排。"),
                entry("subagents/行程规划.md", "---\ndescription: 生成行程。\n---\n\n你是行程规划子智能体。"),
                entry("skills/jc-0-路径工具/SKILL.md", "---\nname: jc-0-路径工具\n---\n\n路径规范。"),
                entry("skills/jc-0-路径工具/references/约定.md", "约定正文"));

        AgentDefinitionImportService.ImportResult r = newService().importFromZip(
                new java.io.ByteArrayInputStream(zip), "旅行规划师");

        Path dir = workspaceRoot.resolve("agents").resolve("旅行规划师");
        assertTrue(Files.isRegularFile(dir.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(dir.resolve("subagents").resolve("行程规划.md")));
        assertTrue(Files.isRegularFile(dir.resolve("skills").resolve("jc-0-路径工具").resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(dir.resolve("skills").resolve("jc-0-路径工具")
                .resolve("references").resolve("约定.md")));
        assertEquals("agents/旅行规划师", r.workspacePath());
    }

    @Test
    void importFromZip_acceptsSingleWrapperDirectory() throws IOException {
        // 现实中大量 zip 会多包一层项目名目录，两种形态都要认
        byte[] zip = zip(entry("my-agent/AGENTS.md", "# 我的智能体\n\n正文。"));

        AgentDefinitionImportService.ImportResult r = newService().importFromZip(
                new java.io.ByteArrayInputStream(zip), "我的智能体");

        assertTrue(Files.isRegularFile(
                workspaceRoot.resolve("agents").resolve("我的智能体").resolve("AGENTS.md")));
        assertEquals("agents/我的智能体", r.workspacePath());
    }

    @Test
    void importFromZip_agentIdMatchesFileBasedDerivation() throws IOException {
        // 目录名决定 id：导入的 agent 必须与「同名的文件型 agent」算出同一个 id，
        // 否则收编成 智能体配置在后会话会串号
        byte[] zip = zip(entry("AGENTS.md", "# 数据助手\n\n正文。"));
        AgentDefinitionImportService service = newService();
        String name = "数据助手";

        String importedId = service.importFromZip(
                new java.io.ByteArrayInputStream(zip), name).agentId();
        String fileBasedId = AgentDirId.of(name).toString();

        assertEquals(fileBasedId, importedId);
    }

    @Test
    void importFromFiles_writesAllEntries() throws IOException {
        List<AgentDefinitionImportService.PathContent> entries = List.of(
                new AgentDefinitionImportService.PathContent(
                        "AGENTS.md", "# 会议纪要助手\n\n正文。".getBytes(StandardCharsets.UTF_8)),
                new AgentDefinitionImportService.PathContent(
                        "subagents/纪要.md", "---\ndescription: 纪要。\n---\n\n正文。".getBytes(StandardCharsets.UTF_8)));

        AgentDefinitionImportService.ImportResult r = newService().importFromFiles(entries, "会议纪要助手");

        Path dir = workspaceRoot.resolve("agents").resolve("会议纪要助手");
        assertTrue(Files.isRegularFile(dir.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(dir.resolve("subagents").resolve("纪要.md")));
        assertEquals("agents/会议纪要助手", r.workspacePath());
    }

    @Test
    void import_rejectsDuplicateDirectoryName() throws IOException {
        byte[] zip = zip(entry("AGENTS.md", "# 重复名\n\n正文。"));
        AgentDefinitionImportService service = newService();
        service.importFromZip(new java.io.ByteArrayInputStream(zip), "重复名");

        // 同名再导一次必须拒绝：否则两个 agent 算出同一个 id，会话互相串号
        assertThrows(ApiException.class,
                () -> service.importFromZip(new java.io.ByteArrayInputStream(zip), "重复名"));
    }

    // ======================== 根目录 AGENTS.md 必需 ========================

    @Test
    void import_rejectsZipWithoutAgentsMd() throws IOException {
        byte[] zip = zip(entry("README.md", "没有 AGENTS.md"));

        ApiException e = assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(zip), "无根文件"));
        assertTrue(e.getMessage().contains("AGENTS.md"), "错误文案须指明缺的是 AGENTS.md");
    }

    @Test
    void import_rejectsFilesWithoutAgentsMd() {
        List<AgentDefinitionImportService.PathContent> entries = List.of(
                new AgentDefinitionImportService.PathContent("notes.md", "只有笔记".getBytes(StandardCharsets.UTF_8)));

        assertThrows(ApiException.class, () -> newService().importFromFiles(entries, "无根文件"));
    }

    @Test
    void import_rejectsEmptyPackage() throws IOException {
        assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(new byte[0]), "空包"));
    }

    // ======================== zip slip 防护 ========================

    @Test
    void import_rejectsParentTraversalEntry() throws IOException {
        byte[] zip = zip(
                entry("AGENTS.md", "正常根文件"),
                entry("../escaped.md", "写到暂存目录之外"));

        assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(zip), "越界包"));
    }

    @Test
    void import_rejectsNestedParentTraversalEntry() throws IOException {
        // 关键用例：先 normalize 再判会漏掉这个形态
        byte[] zip = zip(
                entry("AGENTS.md", "正常根文件"),
                entry("skills/../../escaped.md", "夹在正常路径里的穿越"));

        assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(zip), "隐蔽越界包"));
    }

    @Test
    void import_rejectsAbsolutePathEntry() throws IOException {
        byte[] zip = zip(
                entry("AGENTS.md", "正常根文件"),
                entry("/etc/passwd", "绝对路径条目"));

        assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(zip), "绝对路径包"));
    }

    @Test
    void import_rejectsDrivePrefixEntry() throws IOException {
        byte[] zip = zip(
                entry("AGENTS.md", "正常根文件"),
                entry("c:windows/evil.md", "盘符路径条目"));

        assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(zip), "盘符包"));
    }

    @Test
    void import_rejectsSymlinkEntry() throws IOException {
        // 符号链接条目落盘后会指向暂存目录之外的任意路径，读取时即可越权。
        // JDK 21 的 ZipEntry 无 setUnixMode/getExternalAttributes，unix mode 只能通过
        // Info-ZIP 扩展（id 0x756e）携带，因此这里手工构造 extra 字节。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("AGENTS.md"));
            zos.write("正常根文件".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            ZipEntry link = new ZipEntry("evil-link");
            // extra: id=0x756e, size=3, payload=[0x01, mode hi, mode lo]; mode=0120777 (symlink|0777)
            link.setExtra(new byte[] {0x75, 0x6e, 0x00, 0x03, 0x01,
                    (byte) ((0120777 >> 8) & 0xFF), (byte) (0120777 & 0xFF)});
            zos.putNextEntry(link);
            zos.write("/etc/passwd".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(out.toByteArray()), "符号链接包"));
    }

    @Test
    void import_rejectsTraversalInFileEntries() {
        // 多文件导入走同一套校验
        List<AgentDefinitionImportService.PathContent> entries = List.of(
                new AgentDefinitionImportService.PathContent("AGENTS.md", "正常".getBytes(StandardCharsets.UTF_8)),
                new AgentDefinitionImportService.PathContent("../../escaped.md", "越界".getBytes(StandardCharsets.UTF_8)));

        assertThrows(ApiException.class, () -> newService().importFromFiles(entries, "越界包"));
    }

    @Test
    void import_rejectsEmptyRelativePath() {
        List<AgentDefinitionImportService.PathContent> entries = List.of(
                new AgentDefinitionImportService.PathContent("AGENTS.md", "正常".getBytes(StandardCharsets.UTF_8)),
                new AgentDefinitionImportService.PathContent("  ", "无名".getBytes(StandardCharsets.UTF_8)));

        assertThrows(ApiException.class, () -> newService().importFromFiles(entries, "无名包"));
    }

    // ======================== 体积限额 ========================

    @Test
    void import_rejectsOversizedSingleFile() throws IOException {
        String huge = "x".repeat(300 * 1024); // > 256KB
        byte[] zip = zip(
                entry("AGENTS.md", "正常根文件"),
                entry("skills/big/SKILL.md", huge));

        assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(zip), "超大文件包"));
    }

    @Test
    void import_rejectsOversizedFileEntry() {
        String huge = "x".repeat(300 * 1024);
        List<AgentDefinitionImportService.PathContent> entries = List.of(
                new AgentDefinitionImportService.PathContent("AGENTS.md", "正常".getBytes(StandardCharsets.UTF_8)),
                new AgentDefinitionImportService.PathContent("skills/big/SKILL.md", huge.getBytes(StandardCharsets.UTF_8)));

        assertThrows(ApiException.class, () -> newService().importFromFiles(entries, "超大文件包"));
    }

    @Test
    void import_rejectsTooManyEntries() {
        List<AgentDefinitionImportService.PathContent> entries = new java.util.ArrayList<>();
        entries.add(new AgentDefinitionImportService.PathContent(
                "AGENTS.md", "正常".getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 2100; i++) {
            entries.add(new AgentDefinitionImportService.PathContent("f" + i + ".md", "x".getBytes(StandardCharsets.UTF_8)));
        }

        assertThrows(ApiException.class, () -> newService().importFromFiles(entries, "海量文件包"));
    }

    // ======================== 失败不留痕 ========================

    @Test
    void import_leavesNothingBehindOnRejection() throws IOException {
        // 失败必须不产生半成品目录：否则下次同名导入会被「已存在」挡住
        byte[] zip = zip(
                entry("AGENTS.md", "正常根文件"),
                entry("../escaped.md", "越界"));

        assertThrows(ApiException.class,
                () -> newService().importFromZip(new java.io.ByteArrayInputStream(zip), "失败包"));

        Path dir = workspaceRoot.resolve("agents").resolve("失败包");
        assertFalse(Files.exists(dir), "导入失败不得留下目录");
    }

    // ======================== 辅助 ========================

    private record Entry(String name, String content) {
    }

    private Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Entry e : entries) {
                zos.putNextEntry(new ZipEntry(e.name()));
                zos.write(e.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
