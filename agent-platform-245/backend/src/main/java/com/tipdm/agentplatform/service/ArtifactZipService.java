package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.web.SessionArtifactController;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 会话产物打包：把「本会话 + 全部子会话」的合并树打成 zip 流。
 *
 * <p><strong>为什么复用合并树而不是重新遍历磁盘</strong>：产物面板给用户看到的是一棵
 * 以 {@code artifacts} 为根的合并树（同名目录 union、同名文件并存加短标）。
 * 若打包自己再扫一遍盘，同名文件要么互相覆盖、要么平铺到错误位置；
 * 复用 {@link SessionArtifactController#mergedArtifactRoots} 后，
 * <em>解压得到的文件名与用户在面板上看到的逐字一致</em>。</p>
 *
 * <p><strong>展示名与磁盘定位严格分离</strong>：合并树节点的 {@code path} 是
 * <em>可回取的定位路径</em>（首段可能是子会话 ID），不是磁盘路径；且合并目录节点只保留
 * 首个贡献者的 path。所以 zip 条目名取「合并树中的展示路径」，
 * 回磁盘一律走 {@code resolveArtifactFile}——越权防护只有那一处收口。</p>
 */
@Service
public class ArtifactZipService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactZipService.class);

    private final long maxTotalBytes;
    private final int maxEntries;
    private final int maxDepth;

    public ArtifactZipService(
            @Value("${agent-platform.artifacts-download.max-total-bytes:524288000}")
            long maxTotalBytes,
            @Value("${agent-platform.artifacts-download.max-entries:50000}") int maxEntries,
            @Value("${agent-platform.artifacts-download.max-depth:6}") int maxDepth) {
        this.maxTotalBytes = maxTotalBytes;
        // JDK zip 硬上限 65535，留出余量；深度与产物面板共用一个默认值：
        // 面板看不到的层级不该能被下载，两者不一致会让用户困惑
        this.maxEntries = Math.min(maxEntries, 60000);
        this.maxDepth = maxDepth;
    }

    /** 打包结果统计，供调用方记日志。 */
    public record PackResult(int entries, long bytes, int skipped) { }

    /**
     * 把合并树打成 zip 写入输出流。
     *
     * <p><strong>响应头一旦发出就无法改状态码</strong>：因此调用方必须**先做体积预检**
     * （见 {@link #preflight}）再决定是否开流。开流之后的异常只能记日志——
     * 这也是本方法内所有分支都只log、不抛异常的原因。</p>
     *
     * @param roots       合并树根节点（{@code mergedArtifactRoots} 的产物）
     * @param displayBase 条目名前缀：下载子目录时为该目录在合并树中的路径，
     *                    全量下载为 {@code ""}。<strong>不带尾斜杠</strong>——
     *                    分隔符由本方法在拼接子节点时自己补，传 {@code "a/"} 会得到
     *                    {@code a//b.zip} 这种双斜杠条目名（多数解压工具能容忍，但不合规）
     * @param sessionRoot 本会话目录，用于解析无子会话前缀的条目
     * @param subSessions 子会话白名单
     */
    public PackResult pack(List<ArtifactNode> roots, String displayBase,
                           Path sessionRoot, Set<String> subSessions,
                           OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        // 产物多是 docx/png/zip 等已压缩内容，换压缩率换不来什么，只烧 CPU
        zip.setLevel(Deflater.BEST_SPEED);

        int entries = 0;
        long bytes = 0;
        int skipped = 0;
        int depthBudget = maxDepth;

        // 显式栈遍历：递归在深目录上易爆栈，且便于逐节点做符号链接校验
        Deque<Object[]> stack = new ArrayDeque<>();
        pushChildren(stack, roots, displayBase, depthBudget);
        while (!stack.isEmpty()) {
            Object[] frame = stack.pop();
            ArtifactNode node = (ArtifactNode) frame[0];
            String entryPath = (String) frame[1];
            int depth = (Integer) frame[2];

            if ("dir".equals(node.type())) {
                // 空目录也要建entry：否则解压后少一层目录层级
                if (node.children() != null && node.children().isEmpty()) {
                    writeDirEntry(zip, entryPath);
                    entries++;
                }
                pushChildren(stack, node.children(), entryPath, depth);
                continue;
            }
            if (entries >= maxEntries) {
                skipped++;
                continue;
            }
            // 定位回磁盘：展示名不可信（同名冲突会带 · 短标，甚至可能撞名），
            // 必须用节点的 path 走唯一收口点
            Path disk = SessionArtifactController.resolveArtifactFile(
                    "", "", sessionRoot, subSessions, node.path());
            if (disk == null) {
                log.warn("打包跳过：无法定位磁盘文件 {}", node.path());
                skipped++;
                continue;
            }
            // isRegularFile 会一并挡掉软链/FIFO/设备文件——不能靠 isDirectory 反推类型
            if (!Files.isRegularFile(disk)) {
                skipped++;
                continue;
            }
            long size;
            try {
                size = Files.size(disk);
            } catch (IOException e) {
                // 源文件在预检与打包之间消失（clear 会立即删会话目录）
                log.warn("打包跳过：读不到大小 {}", disk);
                skipped++;
                continue;
            }
            if (bytes + size > maxTotalBytes) {
                // 预检理论上已拦住；这里仍要守，因为文件可能在预检后被追加写入
                log.warn("打包超总量上限，停止写出: {} + {} > {}", bytes, size, maxTotalBytes);
                skipped++;
                break;
            }
            try {
                zip.putNextEntry(new ZipEntry(entryPath));
                bytes += Files.copy(disk, zip);
                zip.closeEntry();
                entries++;
            } catch (NoSuchFileException e) {
                // 打包途中源文件消失：跳过继续，不让整包失败
                skipped++;
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    // 异步超时/取消：唯一的可靠信号，直接抛出交给容器处理
                    throw e;
                }
                log.warn("打包跳过：写入 {} 失败 {}", entryPath, e.getMessage());
                skipped++;
            }
        }
        zip.finish();
        zip.flush();
        log.info("产物打包完成: {} 个条目 / {} 字节 / 跳过 {}", entries, bytes, skipped);
        return new PackResult(entries, bytes, skipped);
    }

    /**
     * 打包前预检：估算条目数与总字节，超限直接抛 413。
     *
     * <p>必须在写响应头**之前**调用——一旦流式响应开始，超限就只能以
     * 「下载到一半坏掉」的形式暴露给用户。</p>
     */
    public void preflight(List<ArtifactNode> roots, String displayBase,
                          Path sessionRoot, Set<String> subSessions) {
        long total = 0;
        int count = 0;
        for (Entry entry : flatten(roots, displayBase)) {
            ArtifactNode node = entry.node();
            if (!"file".equals(node.type())) {
                continue;
            }
            count++;
            if (count > maxEntries) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "ARTIFACT_TOO_MANY_ENTRIES",
                        "产物文件过多（超过 " + maxEntries + " 个），请选择子目录下载");
            }
            Path disk = SessionArtifactController.resolveArtifactFile(
                    "", "", sessionRoot, subSessions, node.path());
            if (disk == null) {
                continue;
            }
            try {
                total += Files.size(disk);
            } catch (IOException e) {
                // 预检阶段读不到大小按0 计，交给打包阶段跳过后续文件
                continue;
            }
            if (total > maxTotalBytes) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "ARTIFACT_TOO_LARGE",
                        "产物体积超过 " + (maxTotalBytes / 1024 / 1024) + "MB 上限，请选择子目录下载");
            }
        }
    }

    /** 展平合并树为「条目名 + 节点」的线性列表，供预检与打包共用遍历规则。 */
    private List<Entry> flatten(List<ArtifactNode> roots, String displayBase) {
        List<Entry> out = new ArrayList<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        pushChildren(stack, roots, displayBase, maxDepth);
        while (!stack.isEmpty()) {
            Object[] frame = stack.pop();
            ArtifactNode node = (ArtifactNode) frame[0];
            String entryPath = (String) frame[1];
            int depth = (Integer) frame[2];
            out.add(new Entry(node, entryPath));
            if ("dir".equals(node.type())) {
                pushChildren(stack, node.children(), entryPath, depth);
            }
        }
        return out;
    }

    private record Entry(ArtifactNode node, String entryPath) { }

    private void pushChildren(Deque<Object[]> stack, List<ArtifactNode> children,
                              String parentPath, int depth) {
        if (children == null || depth <= 0) {
            return;
        }
        // 逆序入栈以保持与列表一致的输出顺序（栈是后进先出）
        List<ArtifactNode> list = new ArrayList<>(children);
        for (int i = list.size() - 1; i >= 0; i--) {
            ArtifactNode child = list.get(i);
            String childPath = parentPath.isEmpty()
                    ? child.name()
                    : parentPath + "/" + child.name();
            stack.push(new Object[]{child, childPath, depth - 1});
        }
    }

    private void writeDirEntry(ZipOutputStream zip, String entryPath) throws IOException {
        String dirName = entryPath.endsWith("/") ? entryPath : entryPath + "/";
        zip.putNextEntry(new ZipEntry(dirName));
        zip.closeEntry();
    }

    /** 便捷：构造本会话 + 子会话的白名单集合。 */
    public static Set<String> subSessionSet(List<String> ids) {
        return new LinkedHashSet<>(ids);
    }
}
