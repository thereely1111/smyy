package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.LibraryNode;
import com.tipdm.agentplatform.domain.LibraryNodeKind;
import com.tipdm.agentplatform.repository.KnowledgeBaseRepository;
import com.tipdm.agentplatform.repository.LibraryNodeRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.LibraryDtos;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 节点正文读取：<b>按 kind 分派</b>的读入口。
 *
 * <p>这是 {@code library://node/<id>} 引用链路的共用地基——
 * HTTP 下载接口与agent 的 {@code library_read} 工具都调这里，判权也只在这里做一次。</p>
 *
 * <p>分派表：</p>
 * <table border="1">
 *   <caption>kind 到正文来源</caption>
 *   <tr><th>kind</th><th>正文来源</th></tr>
 *   <tr><td>doc</td><td>markdown 文件（{@code LibraryBlobStore.readDoc}）</td></tr>
 *   <tr><td>drive</td><td>文件字节（{@code readDriveFile}，返回下载而非正文）</td></tr>
 *   <tr><td>knowledge</td><td>知识库检索（交由 {@code knowledge_search}，此处只回元信息）</td></tr>
 *   <tr><td>folder</td><td><b>子节点清单</b>（逐个判权后列出标题/kind/引用串）</td></tr>
 *   <tr><td>database / web / link</td><td>P2/P3 再实现</td></tr>
 * </table>
 *
 * <p><b>返回文本还是字节？</b>统一返回 {@link Content}：文本 kind 给 text，
 * drive 只给文件名+大小并提示走下载端点——agent 拿到一整个 100MB 的 pdf 塞进上下文
 * 是有害的，应让它走下载链接自行判断。</p>
 */
@Service
public class LibraryContentService {

    /** 单次返回给模型的最大正文长度（字符）；超出截断并标注。 */
    public static final int MAX_TEXT_CHARS = 20_000;

    /**
     * 目录清单最多列出的子节点数；超出截断并提示。
     *
     * <p>为什么需要上限：目录是引用里唯一「内容长度与节点数成正比」的 kind，
     * 一个几百项的目录若全列出来，模型还没读到任何正文就先被清单吃掉整个上下文窗口。
     * 上限让模型看到「这里有 300 项、我先列了前 100」，从而知道该向用户确认而不是
     * 假装看到了全部。</p>
     */
    public static final int MAX_FOLDER_CHILDREN = 100;

    /**
     * 目录清单的尾部指引。
     *
     * <p><b>「不要复述引用串」这句是实测加的</b>：清单里每条都带
     * {@code library://node/<id>}，模型会把整份清单连同 id 原样输出给用户，
     * 用户看到的是一屏协议串而不是人话。必须显式禁止，否则默认行为就是复述。</p>
     */
    private static final String LIST_FOOTER =
            "（以上是目录清单。尖括号内是引用标识，供你继续调用 library_read 使用，"
                    + "**不要把它们复述给用户**，回答时只说文件名即可。）\n";

    private final LibraryBlobStore blobStore;
    private final LibraryAccessService accessService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final LibraryNodeRepository nodeRepository;

    public LibraryContentService(LibraryBlobStore blobStore,
                                 LibraryAccessService accessService,
                                 KnowledgeBaseRepository knowledgeBaseRepository,
                                 LibraryNodeRepository nodeRepository) {
        this.blobStore = blobStore;
        this.accessService = accessService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.nodeRepository = nodeRepository;
    }

    /**
     * 节点正文内容。
     *
     * @param text        文本类正文（doc）；drive 为空串
     * @param truncated   是否因超长被截断
     * @param fileName    drive 文件名；其他 kind 为 null
     * @param fileSize    drive 字节数；其他 kind 为 -1
     */
    public record Content(
            UUID nodeId,
            LibraryNodeKind kind,
            String text,
            boolean truncated,
            String fileName,
            long fileSize,
            String hint) {
    }

    /**
     * 取节点正文（含 ACL 复核）。
     *
     * <p>⚠️ 每次调用都重新判权、<b>不做任何缓存</b>：引用可以出现在历史消息里，
     * 权限可能已被回收，缓存会让已撤销的权限继续生效。</p>
     */
    @Transactional(readOnly = true)
    public Content read(UUID nodeId, Viewer viewer) {
        LibraryNode node = accessService.requireVisibleNode(nodeId, viewer);

        return switch (node.getKind()) {
            case doc -> {
                String text = blobStore.readDoc(node.getSpaceId(), node.getContentRel());
                boolean truncated = text.length() > MAX_TEXT_CHARS;
                if (truncated) {
                    text = text.substring(0, MAX_TEXT_CHARS);
                }
                yield new Content(node.getId(), node.getKind(), text, truncated,
                        null, -1L, truncated ? "正文过长已截断" : null);
            }
            case drive -> {
                String rel = node.getContentRel();
                if (rel == null || rel.isBlank()) {
                    throw ApiException.notFound("文件不存在");
                }
                String name = blobStore.driveOriginalName(node.getSpaceId(), rel);
                long size = blobStore.driveFileSize(node.getSpaceId(), rel);
                yield new Content(node.getId(), node.getKind(), "", false, name, size,
                        "二进制文件，请按需下载后读取，不要整份读入上下文");
            }
            case knowledge -> {
                // 知识库正文不在本模块：回元信息 + 指向 knowledge_search 工具
                var kbId = node.getPayloadKbId();
                String kbName = knowledgeBaseRepository.findById(kbId)
                        .map(kb -> kb.getName())
                        .orElse("未知知识库");
                yield new Content(node.getId(), node.getKind(), "", false, null, -1L,
                        "这是知识库「" + kbName + "」，请改用 knowledge_search 工具检索其内容");
            }
            case folder -> {
                String listing = listFolder(node, viewer);
                yield new Content(node.getId(), node.getKind(), listing, false, null, -1L,
                        "这是目录，以下是其中的条目。需要某个条目的正文时，"
                                + "用它的 library://node/<id> 再调用一次 library_read；"
                                + "子目录同样可以继续读取。");
            }
            default -> throw new ApiException(
                    HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                    "该类型的内容读取尚未实现: " + node.getKind());
        };
    }

    /**
     * 列目录的直接子节点（逐个判权后渲染成清单文本）。
     *
     * <p><b>为什么子节点必须逐个判权</b>：ACL 沿祖先链<b>向上冒泡</b>
     * （授权给某节点即覆盖其整棵子树，见 {@code LibraryAccessService#resolveNodePermission}），
     * 但<b>反向不成立</b>——父目录可见不代表子节点可见，节点级授权可以只授给子树里的
     * 某一支。若这里只判父就整目录输出，等于用父的权限泄漏了未授权子节点的存在与标题。
     * 逐个 {@code resolveNodePermission} 过滤是唯一正确口径。</p>
     *
     * <p>输出每条的 {@code library://node/<id>}（尖括号包裹）：模型拿到清单后要能
     * 直接接着读，只给标题不给引用等于让它去猜 id；尖括号 + 尾部「不要复述」指引
     * 则是为了让模型**自己用**而不回显给用户。</p>
     */
    private String listFolder(LibraryNode folder, Viewer viewer) {
        List<LibraryNode> children = nodeRepository
                .findBySpaceIdAndParentIdAndDeletedAtIsNullOrderBySortOrderAsc(
                        folder.getSpaceId(), folder.getId());
        StringBuilder sb = new StringBuilder(512);
        int shown = 0;
        int hidden = 0;
        for (LibraryNode child : children) {
            if (accessService.resolveNodePermission(child, viewer) == null) {
                // 计数但不列出：让模型知道「这里还有内容但我看不到」，
                // 而不是以为目录就这么空——后者会让它对用户做出错误断言。
                hidden++;
                continue;
            }
            if (shown >= MAX_FOLDER_CHILDREN) {
                continue;
            }
            sb.append("- ").append(child.getTitle())
                    .append("（").append(kindLabel(child.getKind())).append("）")
                    // 尖括号包裹：让引用串在视觉上明显是「内部标识」而非正文内容。
                    // 不加包裹时模型会把整份清单连同 id 原样复述给用户（实测）。
                    .append(" <").append(LibraryDtos.REF_SCHEME).append(child.getId()).append(">")
                    .append('\n');
            shown++;
        }
        int truncatedCount = children.size() - shown - hidden;
        if (truncatedCount > 0) {
            sb.append("（另有 ").append(truncatedCount).append(" 个条目未列出）\n");
        }
        if (hidden > 0) {
            sb.append("（另有 ").append(hidden).append(" 个条目当前用户无权访问）\n");
        }
        if (shown == 0) {
            return hidden > 0 ? "（该目录下没有可访问的条目）\n" : "（该目录为空）\n";
        }
        return LIST_FOOTER + sb;
    }

    /** kind 的中文标签；与前端 {@code libraryNav.kind_*} 同一套称谓。 */
    private static String kindLabel(LibraryNodeKind kind) {
        return switch (kind) {
            case folder -> "文件夹";
            case doc -> "文档";
            case database -> "数据表";
            case web -> "数据页面";
            case link -> "网页剪藏";
            case drive -> "网盘文件";
            case knowledge -> "知识库";
        };
    }

    /**
     * 取 drive 文件字节（供 HTTP 下载端点）。
     * 仅 drive 节点可调用，其他 kind 抛 400。
     */
    @Transactional(readOnly = true)
    public DriveFile readDriveFile(UUID nodeId, Viewer viewer) {
        LibraryNode node = accessService.requireVisibleNode(nodeId, viewer);
        if (node.getKind() != LibraryNodeKind.drive) {
            throw ApiException.badRequest("该节点不是文件类型");
        }
        String rel = node.getContentRel();
        if (rel == null || rel.isBlank()) {
            throw ApiException.notFound("文件不存在");
        }
        return new DriveFile(
                blobStore.readDriveFile(node.getSpaceId(), rel),
                blobStore.driveOriginalName(node.getSpaceId(), rel));
    }

    /** drive 文件内容与其原名。 */
    public record DriveFile(byte[] bytes, String fileName) {
    }
}
