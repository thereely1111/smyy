package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.DocumentPreviewProperties;
import com.tipdm.agentplatform.domain.LibraryNode;
import com.tipdm.agentplatform.domain.LibraryNodeKind;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.error.ApiException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.Optional;
import java.util.UUID;

/**
 * 资料库文档预览工具 {@code document_preview}：让智能体把资料库里的二进制文件
 * （office / pdf / 压缩包 / 图片等）转成可打开的网页预览地址。
 *
 * <p><b>为什么需要它</b>：资料库的 drive 节点只存元信息（文件名 + 大小），
 * 前端也只能强制下载。而 {@code read_file} 只能读文本、{@code read_image} 只能读图，
 * 都无法处理 docx / xlsx / pdf 这类文件。模型需要拿到一个<b>可访问的预览地址</b>
 * 才能据此回答用户「这个文件里写了什么」。</p>
 *
 * <p><b>职责边界</b>：本工具只<b>产出预览地址</b>，不把文件内容读进上下文——
 * 转换由独立的预览服务完成，模型拿到地址后自行决定是否让用户打开。
 * 模型可读文本由知识库那条 Tika 链路负责，两者互补不互相替代。</p>
 *
 * <p><b>装配期 vs 调用期</b>：本工具在装配期注册，但用户身份与判权<b>必须在调用期</b>
 * 从 {@link RuntimeContext} 取——{@code AgentAssemblyService} 按 agentId 缓存装配产物，
 * 构造期注入身份会让同一份产物服务所有会话。与 {@code LibraryReadTool} 同一纪律。</p>
 *
 * <p><b>安全</b>：每次调用都经 {@link LibraryAccessService#requireVisibleNode} 重新判权，
 * 模型无法通过拼造 nodeId 预览他人资料库的文件；回拉环节的窄门由
 * {@link PreviewTicketService} 把关（单次+ 短时效 + 绑定具体路径）。</p>
 */
public final class DocumentPreviewTool {

    /** 工具名：与权限白名单（buildPermissions）一致。 */
    public static final String TOOL_NAME = "document_preview";

    /** 标准 UUID 的字符串长度。 */
    private static final int UUID_LENGTH = 36;

    /** UUID 中第 9 个字符（首个短横线）的下标。 */
    private static final int UUID_DASH_1 = 8;

    /** UUID 中第 14 个字符（第二个短横线）的下标。 */
    private static final int UUID_DASH_2 = 13;

    /** UUID 中第 19 个字符（第三个短横线）的下标。 */
    private static final int UUID_DASH_3 = 18;

    /**
     * uuid 前缀允许的最大长度（含短横线共 36 字符，留一点余量）。
     *
     * <p>超过此长度则视为文件名自带短横线而非落盘 uuid 前缀，不做剥离。</p>
     */
    private static final int MAX_UUID_PREFIX_LENGTH = UUID_LENGTH + 4;

    private final LibraryAccessService accessService;
    private final PreviewTicketService ticketService;
    private final DocumentPreviewClient client;
    private final DocumentPreviewProperties properties;

    public DocumentPreviewTool(LibraryAccessService accessService,
                               PreviewTicketService ticketService,
                               DocumentPreviewClient client,
                               DocumentPreviewProperties properties) {
        this.accessService = accessService;
        this.ticketService = ticketService;
        this.client = client;
        this.properties = properties;
    }

    /**
     * 生成预览地址。
     *
     * @param runtimeContext 运行时上下文，用于取当前登录用户（不可为构造期注入）
     * @param nodeId         资料库节点 id，形如 {@code library://node/<id>} 里的 id
     * @return 给模型看的文字说明，含预览地址或明确的失败原因
     */
    @Tool(
            name = DocumentPreviewTool.TOOL_NAME,
            description =
                    "为资料库里的文件生成在线预览地址。当用户想直接查看某个资料库文件"
                        + "（word/excel/ppt/pdf/图片/代码文本等）的排版内容、而不是只拿到文件名和大小，"
                        + "用该工具传入 library://node/<id> 里的那个 id，返回一个可打开的预览地址。"
                        + "它只返回地址，不返回文件正文；用户点开即可查看。"
                        + "格式能否渲染由预览服务自行判定，不支持时它会给出说明页面。"
                        + "若只是要读文本内容（如问答、摘要），用其他工具或知识库检索，不要用本工具。",
            readOnly = true,
            concurrencySafe = true)
    public String preview(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "node_id",
                            description = "资料库节点 id，即 library://node/<id> 中的那个 id",
                            required = true)
                    String nodeId) {
        if (!properties.enabled()) {
            return "文档预览功能当前未启用，无法生成预览地址。";
        }
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

        // 每次调用重新判权（不缓存）：历史消息里的引用可能已被回收权限
        LibraryNode node;
        try {
            node = accessService.requireVisibleNode(id, Viewer.runtime(currentUser));
        } catch (ApiException e) {
            // 不回显堆栈/内部信息；模型据此知道"取不到"，不要编造
            return "无法访问该资料库节点：可能已被删除，或当前用户没有访问权限。";
        }

        if (node.getKind() != LibraryNodeKind.drive) {
            return "只有资料库里的文件（drive 类型）才能预览；当前节点是 " + node.getKind()
                    + "，请用对应的读取工具。";
        }
        String rel = node.getContentRel();
        if (rel == null || rel.isBlank()) {
            return "该节点没有可预览的文件内容（可能已被删除）。";
        }

        // ⚠️ 不做格式判据（含 CAD）：能否渲染由预览服务自行判定——
        // 它认得 dwg（FileUtils.typeFromFileName 返回 FileType.cad），
        // 认不得的类型也会渲染「不支持该格式」页面。平台侧再判一次只会与它漂移。
        String fileName = fileNameOf(node, rel);

        // 先探活再签票：服务不可用时不必浪费一张票
        try {
            client.ensureAvailable();
        } catch (IllegalStateException e) {
            return "文档预览服务当前不可用，无法生成预览地址："
                    + (e.getMessage() == null ? "未配置" : e.getMessage());
        }

        // 票据带上原始文件名：存量 drive 的落盘名可能已丢扩展名，
        // 预览服务靠后缀判断渲染方式，只给落盘名会让它「认不出格式」
        String ticket = ticketService.issue(node.getSpaceId(), rel, fileName);
        String sourceUrl = properties.publicBaseUrl() + "/api/doc-preview/raw?ticket=" + ticket;

        try {
            String previewUrl = client.buildPreviewUrl(sourceUrl, fileName);
            return "预览地址已生成（" + fileName + "）：\n" + previewUrl
                    + "\n\n把这个地址给用户打开即可看到排版后的内容。"
                    + "若返回的是「服务未启用/不可用」，说明预览服务未配置或未启用。";
        } catch (IllegalStateException e) {
            return "文档预览服务当前不可用，无法生成预览地址："
                    + (e.getMessage() == null ? "未配置" : e.getMessage());
        }
    }

    /**
     * 展示用文件名。
     *
     * <p>drive 的落盘名是 {@code {uuid}-{ascii名}}（{@code LibraryBlobStore} 口径），
     * 直接展示会带一串 uuid；这里去掉前缀，只为让模型/用户看得懂。</p>
     *
     * @param node 已判权可见的节点
     * @param rel  节点内容相对路径（兜底取名时用）
     * @return 去掉 uuid 前缀后的文件名
     */
    private static String fileNameOf(LibraryNode node, String rel) {
        String name = Optional.ofNullable(node.getTitle())
                .filter(s -> !s.isBlank())
                .orElseGet(() -> {
                    int idx = rel.lastIndexOf('/');
                    return idx >= 0 ? rel.substring(idx + 1) : rel;
                });
        // 落盘名前缀形如 "0f3a...-原始名"，去掉 uuid 部分
        int dash = name.indexOf('-');
        if (dash > 0 && dash <= MAX_UUID_PREFIX_LENGTH && isUuidPrefix(name.substring(0, dash))) {
            return name.substring(dash + 1);
        }
        return name;
    }

    /**
     * 判断一个字符串是否为 UUID（长度 + 短横线位置两个特征即可，不做完整解析）。
     *
     * @param s 待判断的串
     * @return 形如 UUID 时为 {@code true}
     */
    private static boolean isUuidPrefix(String s) {
        return s.length() == UUID_LENGTH
                && s.charAt(UUID_DASH_1) == '-'
                && s.charAt(UUID_DASH_2) == '-'
                && s.charAt(UUID_DASH_3) == '-';
    }

    /**
     * 从可能带前缀的串里取 UUID。
     *
     * <p>容错用户粘贴 {@code library://node/<id>} 整体。</p>
     *
     * @param raw 原始输入
     * @return 解析出的 UUID；非法时为 {@code null}
     */
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

    /**
     * 从运行时上下文取当前用户。
     *
     * @param runtimeContext 工具运行时上下文（可能为 null）
     * @return 当前用户 id；无法解析时为 {@code null}
     */
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
