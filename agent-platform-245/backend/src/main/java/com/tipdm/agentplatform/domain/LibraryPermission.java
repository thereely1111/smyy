package com.tipdm.agentplatform.domain;

/**
 * 资料库授权权限：READ / WRITE（WRITE 蕴含 READ）。
 *
 * <p>与 {@link KnowledgeBasePermission} 语义一致但独立成枚举——资料库的授权粒度是
 * 节点（可细化到单个节点），知识库只到库级；两者不共享表，不因枚举复用而耦合演进。</p>
 *
 * <p>无授权为运行时派生结果（返回 {@code null}），不落库。</p>
 */
public enum LibraryPermission {
    READ,
    WRITE
}

