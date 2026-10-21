package com.tipdm.agentplatform.domain;

/**
 * 技能归属范围（2026-09-21，口径对齐 {@link KnowledgeBaseScope} / model_credentials.scope）：
 * - PLATFORM：平台库，创建/编辑/停用/删除需 skill:create/update/status/delete 权限码，
 *   存量数据默认归此；智能体装配与技能沉淀的默认可见库。
 * - PERSONAL：个人库，任何登录用户可建，创建者（owner_user_id）自治，
 *   改/停用/删不需要权限码；他人不可见（404）。
 *
 * <p>枚举常量名与 DB 列值一致（大写），配合 @Enumerated(STRING) 直接映射。</p>
 */
public enum SkillScope {
    PLATFORM,
    PERSONAL
}
