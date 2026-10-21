package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.LibraryNodeKind;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.error.ApiException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.UUID;

/**
 * 资料库读取工具 {@code library_read}：按 nodeId 回读资料库节点正文。
 *
 * <p><b>为什么需要它</b>：对话里引用资料库文件时，消息文本中带的是
 * {@code library://node/<nodeId>}（协议串，零拷贝、单一副本）。
 * 模型的上下文里只有这串协议，<b>没有正文</b>——必须由它主动调本工具回读。
 * 这与 {@code @path} 附件不同：后者由框架的 {@code AtPathExpansionMiddleware}
 * 自动展开注入，本工具是显式拉取。</p>
 *
 * <p><b>装配期 vs 调用期</b>：本工具在装配期注册（对全部智能体可见），
 * 但用户身份与判权<b>必须在调用期</b>从 {@link RuntimeContext} 取——
 * {@code AgentAssemblyService} 按 agentId 缓存装配产物，构造期注入身份会让
 * 同一份产物服务所有会话，越权风险极高。与 {@code KnowledgeSearchTool} 同一纪律。</p>
 *
 * <p><b>安全</b>：每次调用都经 {@link LibraryAccessService#requireVisibleNode}
 * 重新判权（owner ∪ 空间默认权限 ∪ 节点授权 ∪ 群组展开），模型无法通过拼造
 * nodeId 越权读取他人资料库；不可见或已删除的节点回一条明确说明，<b>不回显
 * 系统细节</b>。</p>
 */
public final class LibraryReadTool {

    /** 工具名：与权限白名单（buildPermissions）一致。 */
    public static final String TOOL_NAME = "library_read";

    private final LibraryContentService contentService;

    public LibraryReadTool(LibraryContentService contentService) {
        this.contentService = contentService;
    }

    @Tool(
            name = LibraryReadTool.TOOL_NAME,
            description =
                    "读取资料库中某个节点的正文。当用户消息里出现 library://node/<id> 形式的引用时，"
                        + "用该工具按 id 读取对应资料库文件的实际内容——引用本身不含正文，必须调用本工具才能看到内容。"
                        + "若该 id 指向的是文件夹（目录），返回的是它包含的条目清单（标题、类型、各自的引用串），"
                        + "此时可用清单里的引用串继续调用本工具逐个读取；子目录同样返回清单，可逐层下钻。"
                        + "返回文档全文或文件信息。回答时应直接引用读到的内容。"
                        + "若返回提示改用其他工具（如知识库），按提示执行。"
                        + "普通闲聊、没有 library:// 引用的问题不要调用。",
            readOnly = true,
            concurrencySafe = true)
    public String read(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "node_id",
                            description = "资料库节点 id，即 library://node/<id> 中的那个 id",
                            required = true)
                    String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "错误：node_id 不能为空。";
        }
        UUID id = parseNodeId(nodeId);
        if (id == null) {
            return "错误：node_id 格式非法，应为 UUID。";
        }
        UUID currentUser = currentUser(runtimeContext);
        if (currentUser == null) {
            return "错误：无法解析当前登录用户。";
        }
        try {
            // 运行期入口：Viewer.runtime 按非管理员 fail-close，权限不足时抛 404
            LibraryContentService.Content c =
                    contentService.read(id, Viewer.runtime(currentUser));
            return render(c);
        } catch (ApiException e) {
            // 不回显堆栈/内部信息；模型据此知道"取不到"，不要编造内容
            return "无法读取该资料库节点：可能已被删除，或当前用户没有访问权限。";
        }
    }

    /** 把 Content 渲染成给模型看的文本。 */
    private static String render(LibraryContentService.Content c) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("【资料库节点 ").append(c.nodeId()).append("（").append(c.kind()).append("）】\n");
        if (c.hint() != null && !c.hint().isBlank()) {
            sb.append(c.hint()).append('\n');
        }
        if (c.kind() == LibraryNodeKind.drive) {
            sb.append("文件名：").append(c.fileName()).append('\n');
            sb.append("大小：").append(c.fileSize()).append(" 字节\n");
            sb.append("（二进制文件不直接回读内容；如确需内容，按上面的提示获取下载链接后自行解析）\n");
            return sb.toString();
        }
        if (c.text() == null || c.text().isBlank()) {
            sb.append("（该节点正文为空）\n");
        } else {
            sb.append(c.text()).append('\n');
        }
        if (c.truncated()) {
            sb.append("（正文过长已截断）\n");
        }
        return sb.toString();
    }

    /** 从可能带前缀的串里取 UUID：容错用户粘贴 {@code library://node/<id>} 整体。 */
    private static UUID parseNodeId(String raw) {
        String v = raw.trim();
        int idx = v.lastIndexOf('/');
        if (idx >= 0) {
            v = v.substring(idx + 1);
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID currentUser(RuntimeContext runtimeContext) {
        String userId = runtimeContext != null ? runtimeContext.getUserId() : null;
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
