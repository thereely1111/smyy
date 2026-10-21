package com.tipdm.agentplatform.domain;

/**
 * 授权主体类型：直接授予用户（USER）或群组（GROUP）。
 * 枚举常量名与 DB CHECK 约束值一致（大写）。
 */
public enum GrantPrincipalType {
    USER,
    GROUP
}
