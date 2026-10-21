package com.tipdm.agentplatform.web.dto;

import java.util.List;
import java.util.UUID;

public final class TagDtos {

    private TagDtos() {
    }

    /**
     * 标签视图。消费端（广场 / KB 列表）只需要 id/name；
     * admin 管理接口额外回 sort/enabled/agentCount/kbCount。
     * color 已下线（V55）：选中态统一品牌蓝，不再按标签上彩。
     */
    public record TagView(
            UUID id,
            String name,
            Integer sort,
            Boolean enabled,
            Long agentCount,
            Long kbCount) {

        /** 消费端精简视图：只带展示字段，不带管理字段与计数。 */
        public static TagView brief(TagView v) {
            return new TagView(v.id(), v.name(), null, null, null, null);
        }
    }

    public record TagRequest(
            String name,
            Integer sort,
            Boolean enabled) {
    }

    public record TagListResponse(List<TagView> tags, long total) {
    }
}
