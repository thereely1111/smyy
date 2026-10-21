package com.tipdm.agentplatform.domain;

/**
 * 知识库授权权限：READ / WRITE（WRITE 蕴含 READ）。
 * 注意：NONE 为「无授权」的运行时派生结果，不落库。
 * 枚举常量名与 DB CHECK 约束值一致（大写）。
 */
public enum KnowledgeBasePermission {
    READ,
    WRITE
}
