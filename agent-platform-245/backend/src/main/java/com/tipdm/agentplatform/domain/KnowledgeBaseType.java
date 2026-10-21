package com.tipdm.agentplatform.domain;

/**
 * 知识库类型：
 * - rag：本地语义向量检索（自托管 pgvector，解析→分块→嵌入→入库）
 * - external：外部 RAG 平台（RAGFlow / Dify / 百炼）
 * - text：文本检索（grep / cat 工具，不向量化、不分块、不建索引）
 *
 * <p>枚举常量名与 DB CHECK 约束值一致（小写），配合 @Enumerated(STRING) 直接映射。
 */
public enum KnowledgeBaseType {
    rag,
    external,
    text
}
