package com.tipdm.agentplatform.domain;

/**
 * 知识库文档状态机：
 * - rag：pending → parsing → chunking → indexing → ready（异步向量管道，P3 实现）
 * - text：上传即 ready（无解析/分块/索引）
 * - 任意类型出错置为 error
 *
 * <p>枚举常量名与 DB CHECK 约束值一致（小写）。
 */
public enum KnowledgeDocumentStatus {
    pending,
    parsing,
    chunking,
    indexing,
    ready,
    error
}
