package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.web.dto.SkillRepositoryDtos.SkillRepoCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能仓库接入的纯解析逻辑：SKILL.md frontmatter、目录扫描、JSON 索引。
 * 不起 Spring 上下文，也不联网 —— git/http 的网络同步属外部 I/O，靠人工验证。
 */
class SkillRepositoryServiceTest {

    private final SkillRepositoryService service =
            new SkillRepositoryService(null, new ObjectMapper(), null);

    // ── SKILL.md frontmatter ─────────────────────────────────────────────────

    @Test
    void parsesFrontmatterAndBody() {
        String md = """
                ---
                name: 数据准备
                description: 准备教材数据
                version: 1.2.0
                author: 张三
                tags: [教材, 数据]
                ---

                # 正文标题

                正文内容。
                """;
        var parsed = SkillRepositoryService.parseSkillMarkdown(md, "目录名兜底");
        assertEquals("数据准备", parsed.name());
        assertEquals("准备教材数据", parsed.description());
        assertEquals("1.2.0", parsed.version());
        assertEquals("张三", parsed.author());
        assertEquals(List.of("教材", "数据"), parsed.tags());
        assertTrue(parsed.body().startsWith("# 正文标题"));
    }

    @Test
    void tagsSupportCommaSeparatedWithoutBrackets() {
        var parsed = SkillRepositoryService.parseSkillMarkdown(
                "---\nname: x\ntags: a, b ,c\n---\nbody", null);
        assertEquals(List.of("a", "b", "c"), parsed.tags());
    }

    @Test
    void missingFrontmatterFallsBackToFolderNameAndFullBody() {
        String md = "# 直接正文\n\n没有 frontmatter。\n";
        var parsed = SkillRepositoryService.parseSkillMarkdown(md, "某技能");
        assertEquals("某技能", parsed.name());
        assertNull(parsed.description());
        assertEquals(md, parsed.body());
    }

    @Test
    void handlesCrlfAndBom() {
        String md = "\uFEFF---\r\nname: 编码测试\r\n---\r\n\r\n正文\r\n";
        var parsed = SkillRepositoryService.parseSkillMarkdown(md, null);
        assertEquals("编码测试", parsed.name());
        assertTrue(parsed.body().startsWith("正文"));
    }

    // ── 目录扫描 ─────────────────────────────────────────────────────────────

    @Test
    void scansNestedSkillMarkdownSkippingGit(@TempDir Path root) throws IOException {
        Path skillA = Files.createDirectories(root.resolve("skills/甲"));
        Files.writeString(skillA.resolve("SKILL.md"), "---\nname: 甲\ndescription: 技能甲\n---\n正文A");
        Path skillB = Files.createDirectories(root.resolve("skills/乙/cnested"));
        Files.writeString(skillB.resolve("SKILL.md"), "无 frontmatter 正文");
        Files.createDirectories(root.resolve(".git/hooks"));
        Files.writeString(root.resolve(".git/hooks/SKILL.md"), "name: 不该被扫到");

        List<SkillRepoCard> cards = service.scanSkillMarkdown(root);

        assertEquals(2, cards.size());
        SkillRepoCard a = cards.stream().filter(c -> c.name().equals("甲")).findFirst().orElseThrow();
        assertEquals("skills--甲", a.id()); // id 无斜杠，可安全拼 URL 路径
        assertEquals("技能甲", a.description());
        assertTrue(a.markdown().endsWith("正文A"));
        SkillRepoCard b = cards.stream().filter(c -> c.name().equals("cnested")).findFirst().orElseThrow();
        assertEquals("skills--乙--cnested", b.id());
        assertNull(b.description());
    }

    @Test
    void skillMdAtRepoRootUsesDirNameAsId(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("SKILL.md"), "---\nname: 根技能\n---\n正文");
        List<SkillRepoCard> cards = service.scanSkillMarkdown(root);
        assertEquals(1, cards.size());
        assertEquals(root.getFileName().toString(), cards.get(0).id());
        assertEquals("根技能", cards.get(0).name());
    }

    // ── JSON 索引（http 型） ──────────────────────────────────────────────────

    @Test
    void parsesCanonicalIndexObject() {
        String json = """
                {"skills":[
                  {"name":"甲","description":"技能甲","version":"1.0","author":"张三",
                   "tags":["a","b"],"url":"https://example.com/jia/SKILL.md"},
                  {"name":"乙"}
                ]}
                """;
        List<SkillRepoCard> cards = service.parseSkillIndex(json);
        assertEquals(2, cards.size());
        // 列表按 name 排序，中文码点序未必是插入序，按名查找断言
        SkillRepoCard jia = cards.stream().filter(c -> c.name().equals("甲")).findFirst().orElseThrow();
        assertEquals("甲", jia.id());
        assertEquals("https://example.com/jia/SKILL.md", jia.url());
        assertNull(jia.markdown()); // 正文懒加载
        SkillRepoCard yi = cards.stream().filter(c -> c.name().equals("乙")).findFirst().orElseThrow();
        assertNull(yi.url());
        assertTrue(yi.tags().isEmpty());
    }

    @Test
    void toleratesTopLevelArrayAndSkipsNamelessEntries() {
        String json = """
                [
                  {"description":"没有 name，应被跳过"},
                  {"name":"有效技能","url":"https://example.com/s.md"}
                ]
                """;
        List<SkillRepoCard> cards = service.parseSkillIndex(json);
        assertEquals(1, cards.size());
        assertEquals("有效技能", cards.get(0).name());
    }

    @Test
    void rejectsNonIndexJson() {
        assertThrows(IllegalStateException.class, () -> service.parseSkillIndex("{\"foo\":1}"));
        assertThrows(IllegalStateException.class, () -> service.parseSkillIndex("not json"));
    }

    // ── 其他 ─────────────────────────────────────────────────────────────────

    @Test
    void slugReplacesPathSeparators() {
        assertEquals("a--b--c", SkillRepositoryService.slug("a/b\\c"));
    }

    @Test
    void maskUrlHidesEmbeddedCredentials() {
        assertEquals("https://user:***@host/repo.git",
                SkillRepositoryService.maskUrl("https://user:secret@host/repo.git"));
        assertEquals("https://host/repo.git", SkillRepositoryService.maskUrl("https://host/repo.git"));
    }

    // ── clawhub 型（对齐官方 ClawSkillHub） ──────────────────────────────────

    @Test
    void humanizesTimestampVersionOnly() {
        assertEquals("2026-07-29", SkillRepositoryService.humanizeVersion("0.20260729.110214"));
        assertEquals("1.2.3", SkillRepositoryService.humanizeVersion("1.2.3"));
        assertNull(SkillRepositoryService.humanizeVersion(null));
        // 八位数字不是真日期 → 原样保留
        assertEquals("0.20261399.110214", SkillRepositoryService.humanizeVersion("0.20261399.110214"));
    }

    @Test
    void mapsCatalogRecordWithOwner() throws IOException {
        String item = """
                {"slug":"gifgrep","displayName":"Gif Grep","summary":"Search gifs",
                 "topics":["cli","search"],"latestVersion":{"version":"0.20260729.110214"},
                 "updatedAt":1753785600000,
                 "owner":{"handle":"alice","displayName":"Alice","image":"https://x/a.png"},
                 "ownerHandle":"alice",
                 "canonicalUrl":"/skills/alice/gifgrep",
                 "stats":{"installs":3,"downloads":120}}
                """;
        SkillRepoCard card = service.toClawhubCard(new ObjectMapper().readTree(item), "https://clawhub.ai");
        assertEquals("alice--gifgrep", card.id()); // 官方 id=owner/slug → 无斜杠化
        assertEquals("gifgrep", card.name());       // name 必须是裸 slug
        assertEquals("Search gifs", card.description());
        assertEquals("2026-07-29", card.version());
        assertEquals("Alice", card.author());
        assertEquals(java.time.Instant.ofEpochMilli(1753785600000L), card.updatedAt());
        assertEquals(List.of("cli", "search"), card.tags());
        assertEquals("https://clawhub.ai/skills/alice/gifgrep", card.url());
        assertNull(card.markdown()); // 正文懒加载
    }

    @Test
    void mapsSearchRecordWithoutOwner() throws IOException {
        // 搜索端点：无 latestVersion 对象（version 在顶层）、owner 藏在 native 下、无 canonicalUrl
        String item = """
                {"slug":"pdf-tools","version":"1.2.3","summary":"PDF 工具",
                 "topics":["doc"],
                 "native":{"owner":{"handle":"bob","displayName":"Bob"}},
                 "ownerHandle":"bob",
                 "downloads":7}
                """;
        SkillRepoCard card = service.toClawhubCard(new ObjectMapper().readTree(item), "https://clawhub.ai");
        assertEquals("bob--pdf-tools", card.id());
        assertEquals("1.2.3", card.version()); // 非 timestamp 版本原样保留
        assertEquals("Bob", card.author());
        assertNull(card.updatedAt());
        assertEquals("https://clawhub.ai/skills/pdf-tools", card.url()); // slug 路由兜底（307 到 canonical）
    }

    @Test
    void skipsClawhubEntriesWithoutSlug() throws IOException {
        String item = """
                {"summary":"没有 slug，应被跳过"}
                """;
        assertNull(service.toClawhubCard(new ObjectMapper().readTree(item), "https://clawhub.ai"));
    }

    // ── skillhub 型（skillhub.cn 目录 API） ──────────────────────────────────

    @Test
    void mapsSkillhubCatalogRecord() throws IOException {
        String item = """
                {"slug":"word-docx","name":"Word / DOCX",
                 "description":"English long description","description_zh":"中文描述优先",
                 "version":"1.0.2","ownerName":"ivangdavila",
                 "namespace":{"handle":"clawhub_ivangdavila","publicSlug":"word-docx",
                              "canonicalName":"@clawhub_ivangdavila/word-docx"},
                 "tags":["docx","office"],"category":"office-efficiency",
                 "created_at":1772740044096,"updated_at":1789528918868,"downloads":195408}
                """;
        SkillRepoCard card = service.toSkillhubCard(new ObjectMapper().readTree(item));
        assertEquals("clawhub_ivangdavila--word-docx", card.id()); // handle/slug → 无斜杠化
        assertEquals("Word / DOCX", card.name());                  // 站点展示名
        assertEquals("中文描述优先", card.description());            // description_zh 优先
        assertEquals("1.0.2", card.version());
        assertEquals("ivangdavila", card.author());
        assertEquals(java.time.Instant.ofEpochMilli(1789528918868L), card.updatedAt());
        assertEquals(List.of("docx", "office"), card.tags());
        assertEquals("https://skillhub.cn/skills/clawhub_ivangdavila/word-docx", card.url());
        assertNull(card.markdown()); // 正文懒加载
    }

    @Test
    void mapsSkillhubRecordWithoutNamespaceTagsFallbackCategory() throws IOException {
        // 无 namespace.handle（裸 slug id）、tags 缺省 → category 兜底
        String item = """
                {"slug":"pdf-tools","name":"PDF 工具","description":"处理 PDF",
                 "category":"office-efficiency"}
                """;
        SkillRepoCard card = service.toSkillhubCard(new ObjectMapper().readTree(item));
        assertEquals("pdf-tools", card.id());
        assertEquals("PDF 工具", card.name());
        assertEquals("处理 PDF", card.description());
        assertEquals(List.of("office-efficiency"), card.tags());
        assertNull(card.updatedAt());
        assertEquals("https://skillhub.cn/skills/pdf-tools", card.url());
    }

    @Test
    void skipsSkillhubEntriesWithoutSlug() throws IOException {
        String item = """
                {"name":"没有 slug，应被跳过"}
                """;
        assertNull(service.toSkillhubCard(new ObjectMapper().readTree(item)));
    }

    // ── 目录缓存落盘 ─────────────────────────────────────────────────────────

    /** 构造带真实 props 的服务（主 service 的 props 为 null，catalog 方法要用）。 */
    private SkillRepositoryService serviceWithProps() {
        var props = new com.tipdm.agentplatform.config.SkillRepositoryProperties();
        props.setType("clawhub");
        return new SkillRepositoryService(props, new ObjectMapper(), null);
    }

    @Test
    void catalogRoundTripPreservesCardsAndRefs() throws IOException {
        SkillRepositoryService source = serviceWithProps();
        String original = """
                {"type":"clawhub","repoKey":"%s","syncedAt":"2026-09-16T02:00:00Z",
                 "cards":[{"id":"u1--pdf","name":"pdf","description":"d","version":"1.0",
                           "author":"a","tags":["t"],"updatedAt":1758000000000,
                           "url":"https://clawhub.ai/skills/pdf","markdown":null}],
                 "refs":{"u1--pdf":["u1","pdf"]}}
                """.formatted(sha256OfService(source));
        source.applyCatalogJson(original);
        String saved = source.catalogJson();

        SkillRepositoryService restored = serviceWithProps();
        restored.applyCatalogJson(saved);
        // 再序列化应与上一份一致（往返无损）
        assertEquals(saved, restored.catalogJson());
    }

    @Test
    void catalogIgnoredWhenTypeOrRepoChanged() throws IOException {
        SkillRepositoryService target = serviceWithProps();
        String wrongType = """
                {"type":"skillhub","repoKey":"whatever","syncedAt":"2026-09-16T02:00:00Z",
                 "cards":[{"id":"x","name":"x","description":null,"version":null,
                           "author":null,"tags":[],"updatedAt":null,"url":null,
                           "markdown":null}],"refs":{}}
                """;
        target.applyCatalogJson(wrongType);
        assertTrue(target.catalogJson().contains("\"cards\":[]"));

        String rightTypeWrongRepo = """
                {"type":"clawhub","repoKey":"deadbeef","syncedAt":"2026-09-16T02:00:00Z",
                 "cards":[{"id":"x","name":"x","description":null,"version":null,
                           "author":null,"tags":[],"updatedAt":null,"url":null,
                           "markdown":null}],"refs":{}}
                """;
        target.applyCatalogJson(rightTypeWrongRepo);
        assertTrue(target.catalogJson().contains("\"cards\":[]"));
    }

    /** 复用服务私有 repoKey 算法（effectiveUrl 的 SHA-256），让往返测试对得上。 */
    private static String sha256OfService(SkillRepositoryService s) {
        return s.catalogRepoKeyForTest();
    }
}
