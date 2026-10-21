package com.tipdm.agentplatform.service;

import java.util.List;
import java.util.UUID;

/**
 * 运行期「会话关联知识库」上下文快照。
 *
 * <p>由 {@code PlatformAguiRuntimeContextResolver} 在每轮 AG-UI run 时从
 * {@link SessionKnowledgeService} 读取并以 typed 属性放进 {@code RuntimeContext}；
 * {@code KnowledgeSearchTool} 在工具调用期按同一个类型取回（AG-UI processor
 * 会完整拷贝 resolver 返回的上下文，typed 属性不丢失）。</p>
 */
public record SessionKnowledgeRef(List<UUID> knowledgeBaseIds) {

    /** 无关联时的常量，避免每轮 create。 */
    public static final SessionKnowledgeRef NONE = new SessionKnowledgeRef(List.of());

    public SessionKnowledgeRef {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
    }
}
