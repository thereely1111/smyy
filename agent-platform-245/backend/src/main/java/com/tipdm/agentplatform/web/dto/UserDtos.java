package com.tipdm.agentplatform.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * 用户检索的接口契约（授权选人用，见 {@code UserSearchService}）。
 *
 * <p><b>不含邮箱与手机号</b>：这是授权选人的最小必要信息，多给一列就多泄露一份个人信息。</p>
 */
public final class UserDtos {

    private UserDtos() {
    }

    /**
     * 一个候选用户。
     *
     * @param userId     提交给「加入群组」用的 id
     * @param displayName 展示名（{@code name} 为空时回退 username，与群组成员列表同规则）
     * @param username   用户名：{@code name} 为空时用户靠它辨认，也用于搜索高亮
     */
    public record UserSearchHit(UUID userId, String displayName, String username) {
    }

    /**
     * @param hits    候选列表，最多 {@code UserSearchService.MAX_LIMIT} 条
     * @param total   本次命中条数（= {@code hits.size()}；截断后它代表"已展示多少"而非"共多少"）
     * @param keyword 归一化后的关键词，便于前端回显
     */
    public record UserSearchResponse(List<UserSearchHit> hits, int total, String keyword) {
    }
}
