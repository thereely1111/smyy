package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.KnowledgeBaseView;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.SearchKnowledgeBaseResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.SearchResult;
import com.tipdm.agentplatform.web.error.ApiException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.UUID;

/**
 * 会话知识库检索工具 {@code knowledge_search}（需求 2 的运行期检索入口）。
 *
 * <p>该工具在<b>装配期</b>注册（对全部智能体可见），但每次调用的检索范围来自
 * <b>请求期</b> {@link RuntimeContext} 里的 {@link SessionKnowledgeRef}（由
 * {@code PlatformAguiRuntimeContextResolver} 每轮注入）——这是智能体按 agentId
 * 缓存、而知识库关联按会话挂载时，唯一能让同一装配产物响应不同会话的办法。</p>
 *
 * <p>装配期零知识库依赖：未关联知识库的会话调用本工具会得到明确提示，模型据此
 * 不再无谓调用；关联了库的会话，模型会从系统提示（{@code SessionKnowledgeContextMiddleware}
 * 注入的库清单）知道检索入口存在。</p>
 *
 * <p>安全：每个库检索前都经 {@link KnowledgeBaseService#get} 的可见性校验（owner ∪
 * 直接授权 ∪ 群组授权），模型无法通过拼造 kbId 越权读取他人知识库；不可见或
 * 不存在的库直接跳过、不回显错误。</p>
 */
public final class KnowledgeSearchTool {

    /** 工具名：与权限白名单（buildPermissions）及前端 tool-summary 渲染约定对齐。 */
    public static final String TOOL_NAME = "knowledge_search";

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;

    /** 单次调用输出上限（字符）：多库 × topK 的片段拼接后不应击穿上下文。 */
    private static final int MAX_OUTPUT_CHARS = 20000;

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeSearchTool(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Tool(
            name = KnowledgeSearchTool.TOOL_NAME,
            description =
                    "在会话已关联的知识库中检索与问题相关的内容，返回匹配的文本片段。"
                        + "当回答需要引用知识库材料、或者需要确认知识库中是否存在相关依据时调用；"
                        + "一次调用会覆盖全部关联知识库。检索结果即知识库原文片段，"
                        + "回答时应优先基于片段内容作答并注明出处；"
                        + "片段里的文件路径（如 <docId>/文件名.md:行号）只是出处标注，"
                        + "知识库目录不挂载到工作区，不要尝试用 read_file 等工具去读取这些文件；"
                        + "未关联知识库时会返回提示。"
                        + "普通闲聊、与知识库无关的问题不要调用。",
            readOnly = true,
            concurrencySafe = true)
    public String search(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "query",
                            description = "检索问题：用自然语言描述需要的内容主题，尽量包含专有名词以命中原文",
                            required = true)
                    String query,
            @ToolParam(
                            name = "top_k",
                            description = "每个知识库返回的片段数（默认 5，最大 10）",
                            required = false)
                    Integer topK) {
        if (query == null || query.isBlank()) {
            return "错误：query 不能为空。";
        }
        SessionKnowledgeRef ref =
                runtimeContext != null ? runtimeContext.get(SessionKnowledgeRef.class) : null;
        List<UUID> kbIds = ref != null ? ref.knowledgeBaseIds() : List.of();
        if (kbIds.isEmpty()) {
            return "当前会话未关联任何知识库，无法检索。如需使用知识库，请先在对话框中关联。";
        }
        UUID currentUser = currentUser(runtimeContext);
        if (currentUser == null) {
            return "错误：无法解析当前登录用户。";
        }
        int k = topK == null || topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);

        StringBuilder sb = new StringBuilder(1024);
        int totalResults = 0;
        for (UUID kbId : kbIds) {
            KnowledgeBaseView kb;
            SearchKnowledgeBaseResponse resp;
            try {
                // get() 内部 requireVisible：不可见/不存在 → ApiException，跳过该库
                // 运行期入口无接口层裁决环节：Viewer.runtime 按非管理员 fail-close
                kb = knowledgeBaseService.get(kbId, Viewer.runtime(currentUser));
                resp = knowledgeBaseService.search(kbId, query, k, Viewer.runtime(currentUser));
            } catch (ApiException e) {
                continue;
            }
            if (resp == null || resp.results() == null || resp.results().isEmpty()) {
                continue;
            }
            if (sb.length() >= MAX_OUTPUT_CHARS) {
                sb.append("\n（其余知识库结果已截断）");
                break;
            }
            sb.append("\n【知识库：").append(kb.name()).append("】\n");
            int index = 1;
            for (SearchResult r : resp.results()) {
                if (sb.length() >= MAX_OUTPUT_CHARS) {
                    sb.append("\n（结果过长已截断）");
                    break;
                }
                String source = resolveSource(r);
                sb.append(index++).append(". ");
                if (!source.isBlank()) {
                    sb.append("[").append(source).append("] ");
                }
                sb.append(r.content() == null ? "" : r.content().strip()).append("\n");
                appendContextLines(sb, r);
                totalResults++;
            }
        }
        if (totalResults == 0) {
            return "在关联知识库中未检索到与查询相关的内容。";
        }
        sb.setLength(Math.min(sb.length(), MAX_OUTPUT_CHARS));
        return sb.toString().strip()
                + "\n（以上片段来自会话关联知识库，回答时请直接引用片段内容；"
                + "片段中的文件路径仅作出处标注，知识库文件不挂载在工作区，不要尝试读取）";
    }

    /** 片段出处标注：text 型为 file:line，rag/external 型为文档 ID 短前缀。 */
    private static String resolveSource(SearchResult r) {
        if (r.file() != null && !r.file().isBlank()) {
            return r.line() != null ? r.file() + ":" + r.line() : r.file();
        }
        if (r.documentId() != null) {
            String id = r.documentId().toString();
            return id.length() > 8 ? id.substring(0, 8) : id;
        }
        return "";
    }

    private static void appendContextLines(StringBuilder sb, SearchResult r) {
        if (r.context() == null || r.context().isEmpty()) {
            return;
        }
        int contextLines =
                Math.min(r.context().size(), 2);
        for (int i = 0; i < contextLines; i++) {
            String line = r.context().get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            sb.append("  ┆ ").append(line.strip()).append("\n");
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
