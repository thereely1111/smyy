package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.KnowledgeBaseService;
import com.tipdm.agentplatform.service.SessionKnowledgeRef;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.error.ApiException;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话知识库提示中间件（需求 2）：把「本会话已关联哪些知识库」织进系统提示。
 *
 * <p>模型是<b>先决性</b>的：如果它不知道会话挂了知识库，就永远不会调用
 * {@code knowledge_search} 工具。本中间件在每轮 run 的
 * {@link MiddlewareBase#onSystemPrompt} 钩子里读取请求期注入的
 * {@link SessionKnowledgeRef}，把可见库的名字清单追加到系统提示末尾，
 * 并明确指示模型在相关提问时先检索再作答。</p>
 *
 * <p>库名逐个经可见性校验（与工具内一致）：不可见/不存在的库静默跳过，
 * 不向模型泄露他人资源的存在性。每轮 run 会额外触发 N 次 KB 读取（N=关联库数），
 * 量级可忽略。</p>
 */
public class SessionKnowledgeContextMiddleware implements MiddlewareBase {

    private final KnowledgeBaseService knowledgeBaseService;

    public SessionKnowledgeContextMiddleware(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        SessionKnowledgeRef ref = ctx != null ? ctx.get(SessionKnowledgeRef.class) : null;
        if (ref == null || ref.knowledgeBaseIds().isEmpty() || prompt == null) {
            return Mono.just(prompt);
        }
        UUID currentUser = currentUser(ctx);
        if (currentUser == null) {
            return Mono.just(prompt);
        }
        List<String> names = new ArrayList<>();
        for (UUID kbId : ref.knowledgeBaseIds()) {
            try {
                names.add(knowledgeBaseService.get(kbId, Viewer.runtime(currentUser)).name());
            } catch (ApiException ignored) {
                // 不可见/不存在：不向模型披露
            }
        }
        if (names.isEmpty()) {
            return Mono.just(prompt);
        }
        String kbSection =
                "\n\n[会话关联知识库] 本会话已关联以下知识库："
                        + String.join("、", names)
                        + "。回答涉及这些知识库内容的问题时，必须先调用 knowledge_search"
                        + " 工具检索相关片段，再基于检索结果作答并说明出处；"
                        + "检索结果不足时如实说明，不要编造知识库内容。";
        return Mono.just(prompt + kbSection);
    }

    private static UUID currentUser(RuntimeContext ctx) {
        String userId = ctx != null ? ctx.getUserId() : null;
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
