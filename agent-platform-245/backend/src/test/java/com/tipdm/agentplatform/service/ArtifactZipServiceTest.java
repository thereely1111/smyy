package com.tipdm.agentplatform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tipdm.agentplatform.web.SessionArtifactController;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode;
import com.tipdm.agentplatform.web.error.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 产物 zip 打包回归。
 *
 * <p>核心不变量：**解压得到的文件名必须与用户在产物面板上看到的逐字一致**。
 * 这意味着条目名取自「合并树的展示路径」，而回磁盘必须走节点的定位 path——
 * 两者混用会让子会话产物平铺到错误位置，或在同名冲突时互相覆盖。</p>
 */
class ArtifactZipServiceTest {

    @TempDir
    Path runtimeRoot;

    private ArtifactZipService service;
    private Path sessionRoot;

    @BeforeEach
    void setUp() throws IOException {
        service = new ArtifactZipService(524288000L, 50000, 6);
        sessionRoot = runtimeRoot.resolve("agent-1/main-abc");
        // 主会话：书/前言.md
        Files.createDirectories(sessionRoot.resolve("artifacts/书"));
        Files.writeString(sessionRoot.resolve("artifacts/书/前言.md"), "父前言");
        // 子会话：同名文件 + 独有目录
        Files.createDirectories(runtimeRoot.resolve("agent-1/sub-1111aaaa/artifacts/书/组件"));
        Files.writeString(runtimeRoot.resolve(
                "agent-1/sub-1111aaaa/artifacts/书/前言.md"), "子前言");
        Files.writeString(runtimeRoot.resolve(
                "agent-1/sub-1111aaaa/artifacts/书/组件/知识准备.md"), "子组件");
        // 运行态内部目录：不该进包
        Files.createDirectories(sessionRoot.resolve("memory"));
        Files.writeString(sessionRoot.resolve("memory/内部.md"), "内部");
    }

    /** 构造与产物面板完全一致的合并树。 */
    private List<ArtifactNode> mergedRoots() {
        var own = SessionArtifactController.collectChildren(
                sessionRoot.resolve("artifacts"), "artifacts/", 6);
        Map<String, List<ArtifactNode>> subs = new LinkedHashMap<>();
        subs.put("sub-1111aaaa", SessionArtifactController.collectChildren(
                runtimeRoot.resolve("agent-1/sub-1111aaaa/artifacts"),
                "sub-1111aaaa/artifacts/", 6));
        return SessionArtifactController.mergedArtifactRoots("artifacts", own, subs);
    }

    private Map<String, String> packAndRead(Set<String> subSessions) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.pack(mergedRoots(), "", sessionRoot, subSessions, out);
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(out.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entries.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /**
     * 同名文件在包内并存：子会话那份带会话短标，且**内容各归各位**——
     * 这是「展示名与磁盘定位分离」的最终验证。
     */
    @Test
    void packsSameNamedFilesSideBySideWithCorrectContent() throws IOException {
        Map<String, String> entries = packAndRead(Set.of("sub-1111aaaa"));

        String parentKey = "artifacts/书/前言.md";
        String subKey = "artifacts/书/前言.md · 1111aaaa";
        assertTrue(entries.containsKey(parentKey), entries.keySet().toString());
        assertTrue(entries.containsKey(subKey), entries.keySet().toString());
        assertEquals("父前言", entries.get(parentKey));
        assertEquals("子前言", entries.get(subKey));
    }

    /** 子会话的独有文件出现在合并树里的正确位置，不因定位 path 带前缀而跑错目录。 */
    @Test
    void packsSubSessionOnlyFileUnderDisplayPath() throws IOException {
        Map<String, String> entries = packAndRead(Set.of("sub-1111aaaa"));
        String key = "artifacts/书/组件/知识准备.md";
        assertTrue(entries.containsKey(key), entries.keySet().toString());
        assertEquals("子组件", entries.get(key));
    }

    /** 白名单外的子会话其产物不进包（越权防线在打包路径同样生效）。 */
    @Test
    void skipsSessionsOutsideWhitelist() throws IOException {
        Map<String, String> entries = packAndRead(Set.of());
        assertFalse(entries.containsKey("artifacts/书/前言.md · 1111aaaa"),
                entries.keySet().toString());
        assertFalse(entries.containsKey("artifacts/书/组件/知识准备.md"),
                entries.keySet().toString());
    }

    /**
     * 符号链接文件不进包：会话目录模型可写，一个指向宿主的软链会被原样打进产物。
     */
    @Test
    void skipsSymlinkedFile() throws IOException {
        Path outside = runtimeRoot.resolve("agent-1/host-secret.txt");
        Files.writeString(outside, "TOP-SECRET");
        try {
            Files.createSymbolicLink(sessionRoot.resolve("artifacts/泄露.md"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 不支持软链的平台跳过
        }
        Map<String, String> entries = packAndRead(Set.of());
        for (Map.Entry<String, String> en : entries.entrySet()) {
            assertFalse(en.getValue().contains("TOP-SECRET"),
                    "符号链接内容泄漏进包: " + en.getKey());
        }
    }

    /** 总量超限在**开流之前**就被拒：状态码还能是 413，而不是下载到一半坏掉。 */
    @Test
    void preflightRejectsOversizedBeforeStreaming() {
        ArtifactZipService tiny = new ArtifactZipService(4L, 50000, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> tiny.preflight(mergedRoots(), "", sessionRoot, Set.of("sub-1111aaaa")));
        assertEquals(413, ex.getStatus().value());
    }

    /** 条目数超限同样在开流前拒绝。 */
    @Test
    void preflightRejectsTooManyEntries() {
        ArtifactZipService tiny = new ArtifactZipService(524288000L, 1, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> tiny.preflight(mergedRoots(), "", sessionRoot, Set.of("sub-1111aaaa")));
        assertEquals(413, ex.getStatus().value());
    }

    /** 空目录也建entry：否则解压后少一层目录层级。 */
    @Test
    void keepsEmptyDirectories() throws IOException {
        Files.createDirectories(sessionRoot.resolve("artifacts/空目录"));
        Map<String, String> entries = packAndRead(Set.of());
        assertTrue(entries.containsKey("artifacts/空目录/"),
                entries.keySet().toString());
    }

    /**
     * 子目录下载只含该目录的内容——不能打成全量产物。
     *
     * <p>这是菜单「下载为 zip」的直接后端行为：子树必须从**合并树**里按定位path
     * 定位。若退化为按磁盘路径扫盘，子会话在同名目录下的内容会整体丢失。</p>
     */
    @Test
    void subtreeDownloadCoversMergedContentOnlyOfThatDir() throws IOException {
        var target = new SessionArtifactController.DownloadTarget(
                true, null, "书.zip", "artifacts/书");
        var resolved = target.resolve(mergedRoots());
        assertFalse(resolved.roots().isEmpty(), "合并树里应能定位到 artifacts/书");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.pack(resolved.roots(), resolved.displayBase(), sessionRoot, Set.of("sub-1111aaaa"), out);
        Map<String, String> entries = readZip(out.toByteArray());

        assertEquals("父前言", entries.get("书/前言.md"));
        assertEquals("子前言", entries.get("书/前言.md · 1111aaaa"));
        // 子会话在该目录下的子目录内容也必须在——这正是合并树而非磁盘扫描的价值
        assertEquals("子组件", entries.get("书/组件/知识准备.md"));
        // 目录外的东西不能混进来
        assertFalse(entries.containsKey("前言.md"), entries.keySet().toString());
    }

    /**
     * 解压出来的顶层就是用户点开的那个目录——子会话前缀不得泄漏。
     *
     * <p>回归的真实形态：子会话里有个 {@code _temp} 目录，定位 path 是
     * {@code sub-299b92cf…/artifacts/_temp}。若拿定位 path 当条目名前缀，
     * 解压出来会是 {@code sub-299b92cf…/artifacts/_temp/xxx.py}——
     * 比面板上的树多两层，且与 {@code _temp.zip} 这个文件名完全对不上。</p>
     */
    @Test
    void subtreeDownloadNeverLeaksSubSessionPrefixIntoEntries() throws IOException {
        // 只存在于子会话的目录：父会话没有，合并后其 path 指向子会话
        Files.createDirectories(runtimeRoot.resolve("agent-1/sub-1111aaaa/artifacts/_temp"));
        Files.writeString(runtimeRoot.resolve(
                "agent-1/sub-1111aaaa/artifacts/_temp/结构裁剪探针.py"), "父本没有");

        String locatePath = "sub-1111aaaa/artifacts/_temp";
        var target = new SessionArtifactController.DownloadTarget(true, null, "_temp.zip", locatePath);
        var resolved = target.resolve(mergedRoots());
        assertEquals("_temp", resolved.displayBase(), "前缀必须是展示名，不是定位 path");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.pack(resolved.roots(), resolved.displayBase(), sessionRoot,
                Set.of("sub-1111aaaa"), out);
        Map<String, String> entries = readZip(out.toByteArray());

        assertEquals(Set.of("_temp/结构裁剪探针.py"), entries.keySet(),
                "解压结构应恰好是 _temp/ 一层，与 _temp.zip 文件名互相印证");
    }

    /** 全量下载（subtreePath 为 null）返回合并树原样，前缀空串。 */
    @Test
    void fullDownloadKeepsWholeMergedTree() {
        var target = new SessionArtifactController.DownloadTarget(
                true, null, "artifacts.zip", null);
        var resolved = target.resolve(mergedRoots());
        assertEquals("", resolved.displayBase());
        assertEquals(mergedRoots(), resolved.roots());
    }

    /** 定位不到子树时返回空列表（调用方转 404），不退回全量。 */
    @Test
    void subtreeDownloadMissesToEmptyInsteadOfWholeTree() {
        var target = new SessionArtifactController.DownloadTarget(
                true, null, "x.zip", "artifacts/不存在");
        assertTrue(target.resolve(mergedRoots()).roots().isEmpty());
    }

    private Map<String, String> readZip(byte[] zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entries.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}