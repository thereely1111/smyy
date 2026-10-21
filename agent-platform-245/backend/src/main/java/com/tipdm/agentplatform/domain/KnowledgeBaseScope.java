package com.tipdm.agentplatform.domain;

/**
 * 知识库归属范围（2026-09-20 个人知识库开放需求）：
 * - PLATFORM：平台库，持 knowledge:create 权限码方可创建；存量数据默认归此。
 * - PERSONAL：个人库，任何登录用户可创建，创建者私有。
 *
 * <p>口径对齐 {@code model_credentials.scope}（V48）：scope 仅做归属分类与展示，
 * <b>不改变可见性判定</b>——平台库被他人使用仍需显式授权（owner/USER/GROUP grant），
 * 数据级权限控制后续按需补充。</p>
 *
 * <p>枚举常量名与 DB 列值一致（大写），配合 @Enumerated(STRING) 直接映射。</p>
 */
public enum KnowledgeBaseScope {
    PLATFORM,
    PERSONAL
}
