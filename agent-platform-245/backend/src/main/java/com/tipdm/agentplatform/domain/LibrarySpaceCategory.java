package com.tipdm.agentplatform.domain;

/**
 * 资料库空间类别：
 * - personal：个人空间，每用户至多一个（DB 部分唯一索引约束），后端按用户惰性创建。
 * - team：团队协作空间，每人可建多个，需显式授权他人。
 *
 * <p>枚举常量名与 DB CHECK 约束值一致（小写），配合 {@code @Enumerated(STRING)} 直接映射。</p>
 */
public enum LibrarySpaceCategory {
    personal,
    team
}

