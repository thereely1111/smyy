package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentTag;
import com.tipdm.agentplatform.domain.KnowledgeBaseTag;
import com.tipdm.agentplatform.domain.Tag;
import com.tipdm.agentplatform.repository.AgentTagRepository;
import com.tipdm.agentplatform.repository.KnowledgeBaseTagRepository;
import com.tipdm.agentplatform.repository.TagRepository;
import com.tipdm.agentplatform.web.dto.TagDtos.TagRequest;
import com.tipdm.agentplatform.web.dto.TagDtos.TagView;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 标签服务（2026-09-20）：管理员统一定义的封闭标签集。
 *
 * <p>挂载语义 = <b>全量替换</b>：setAgentTags / setKnowledgeBaseTags 在同一事务内
 * 先删后插，请求里不传（null）表示「本次不碰标签」，传空数组表示「清空标签」。</p>
 */
@Service
public class TagService {

    private final TagRepository tagRepository;
    private final AgentTagRepository agentTagRepository;
    private final KnowledgeBaseTagRepository knowledgeBaseTagRepository;

    public TagService(TagRepository tagRepository,
                      AgentTagRepository agentTagRepository,
                      KnowledgeBaseTagRepository knowledgeBaseTagRepository) {
        this.tagRepository = tagRepository;
        this.agentTagRepository = agentTagRepository;
        this.knowledgeBaseTagRepository = knowledgeBaseTagRepository;
    }

    // ---------- 管理端 ----------

    /** 管理端全量清单：含停用标签与两侧使用计数。 */
    public List<TagView> listAdmin() {
        Map<UUID, Long> agentCounts = countAgentTags();
        Map<UUID, Long> kbCounts = countKnowledgeBaseTags();
        return tagRepository.findAllByOrderBySortAscNameAsc().stream()
                .map(t -> toAdminView(t,
                        agentCounts.getOrDefault(t.getId(), 0L),
                        kbCounts.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    @Transactional
    public TagView create(TagRequest request) {
        Tag tag = new Tag();
        apply(tag, request, true);
        tagRepository.save(tag);
        return toAdminView(tag, 0L, 0L);
    }

    @Transactional
    public TagView update(UUID id, TagRequest request) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("标签不存在: " + id));
        apply(tag, request, false);
        tagRepository.save(tag);
        return toAdminView(tag,
                agentTagRepository.findByTagId(id).size(),
                knowledgeBaseTagRepository.findByTagId(id).size());
    }

    /**
     * 删除标签：先清两张关联表，再删本体（FK 也有 CASCADE，这里显式删是为了
     * 在同一事务内语义完整、且能先拿到删除前的计数做日志）。
     */
    @Transactional
    public void delete(UUID id) {
        if (!tagRepository.existsById(id)) {
            throw ApiException.notFound("标签不存在: " + id);
        }
        agentTagRepository.deleteByTagId(id);
        knowledgeBaseTagRepository.deleteByTagId(id);
        tagRepository.deleteById(id);
    }

    // ---------- 公开标签池 ----------

    /** 消费端标签池：只回启用项，按 sort, name 排序，精简视图。 */
    public List<TagView> listPublic() {
        return tagRepository.findByEnabledTrueOrderBySortAscNameAsc().stream()
                .map(t -> new TagView(t.getId(), t.getName(), null, null, null, null))
                .toList();
    }

    // ---------- 挂载（全量替换） ----------

    /**
     * 按名称解析标签视图（文件驱动智能体 frontmatter 只存名字）：忽略大小写匹配，
     * 未知标签名静默跳过，结果顺序跟随传入名单。
     */
    public List<TagView> findViewsByNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Map<String, Tag> byLower = new LinkedHashMap<>();
        tagRepository.findAll().forEach(t -> byLower.put(t.getName().toLowerCase(), t));
        List<TagView> views = new ArrayList<>();
        for (String name : names) {
            Tag tag = byLower.get(name.trim().toLowerCase());
            if (tag != null) {
                views.add(new TagView(tag.getId(), tag.getName(),
                        null, null, null, null));
            }
        }
        return views;
    }

    @Transactional
    public void setAgentTags(UUID agentId, List<UUID> tagIds) {
        replaceTags(agentId, tagIds,
                agentTagRepository::deleteAllForAgent,
                tagId -> agentTagRepository.save(new AgentTag(agentId, tagId)));
    }

    @Transactional
    public void setKnowledgeBaseTags(UUID kbId, List<UUID> tagIds) {
        replaceTags(kbId, tagIds,
                knowledgeBaseTagRepository::deleteAllForKnowledgeBase,
                tagId -> knowledgeBaseTagRepository.save(new KnowledgeBaseTag(kbId, tagId)));
    }

    /**
     * 全量替换：先 bulk delete 清掉旧挂载（JPQL 直接执行、不走持久化上下文，
     * 避免同 PK 先 insert 后 delete 的 flush 顺序导致唯一键冲突），再插入新集合。
     */
    private void replaceTags(UUID ownerId, List<UUID> tagIds,
                             java.util.function.Consumer<UUID> clearAll,
                             java.util.function.Consumer<UUID> inserter) {
        List<UUID> requested = tagIds == null ? List.of() : tagIds.stream().distinct().toList();
        clearAll.accept(ownerId);
        if (requested.isEmpty()) {
            return;
        }
        // 校验全部存在（不要求 enabled：已停用标签的既有挂载仍可保留）
        List<Tag> tags = tagRepository.findAllById(requested);
        if (tags.size() != requested.size()) {
            Set<UUID> found = new HashSet<>();
            tags.forEach(t -> found.add(t.getId()));
            List<UUID> missing = requested.stream().filter(id -> !found.contains(id)).toList();
            throw ApiException.badRequest("标签不存在: " + missing);
        }
        requested.forEach(inserter);
    }

    // ---------- 批量读取（响应拼装） ----------

    /** 批量取智能体标签：agentId → 精简 TagView 列表（含停用标签，展示一致性优先）。 */
    public Map<UUID, List<TagView>> tagsByAgentIds(Collection<UUID> agentIds) {
        Map<UUID, List<TagView>> result = new LinkedHashMap<>();
        if (agentIds == null || agentIds.isEmpty()) {
            return result;
        }
        Map<UUID, Tag> tagById = tagMap();
        for (UUID agentId : agentIds) {
            List<TagView> views = new ArrayList<>();
            for (AgentTag link : agentTagRepository.findByAgentId(agentId)) {
                Tag tag = tagById.get(link.getTagId());
                if (tag != null) {
                    views.add(new TagView(tag.getId(), tag.getName(),
                            null, null, null, null));
                }
            }
            result.put(agentId, views);
        }
        return result;
    }

    /** 批量取知识库标签：kbId → 精简 TagView 列表。 */
    public Map<UUID, List<TagView>> tagsByKnowledgeBaseIds(Collection<UUID> kbIds) {
        Map<UUID, List<TagView>> result = new LinkedHashMap<>();
        if (kbIds == null || kbIds.isEmpty()) {
            return result;
        }
        Map<UUID, Tag> tagById = tagMap();
        for (UUID kbId : kbIds) {
            List<TagView> views = new ArrayList<>();
            for (KnowledgeBaseTag link : knowledgeBaseTagRepository.findByKnowledgeBaseId(kbId)) {
                Tag tag = tagById.get(link.getTagId());
                if (tag != null) {
                    views.add(new TagView(tag.getId(), tag.getName(),
                            null, null, null, null));
                }
            }
            result.put(kbId, views);
        }
        return result;
    }

    /** 按标签取已挂载的数据库智能体 id 集合（文件驱动智能体不落关联表，不在此列）。 */
    public Set<UUID> agentIdsByTag(UUID tagId) {
        Set<UUID> ids = new HashSet<>();
        agentTagRepository.findByTagId(tagId).forEach(l -> ids.add(l.getAgentId()));
        return ids;
    }

    /** 按标签取已挂载的知识库 id 集合。 */
    public Set<UUID> knowledgeBaseIdsByTag(UUID tagId) {
        Set<UUID> ids = new HashSet<>();
        knowledgeBaseTagRepository.findByTagId(tagId).forEach(l -> ids.add(l.getKnowledgeBaseId()));
        return ids;
    }

    /** 解析标签名（文件驱动智能体的 frontmatter 只存名字，筛选时按名匹配）。 */
    public String tagName(UUID tagId) {
        return tagRepository.findById(tagId).map(Tag::getName).orElse(null);
    }

    // ---------- 内部 ----------

    private Map<UUID, Tag> tagMap() {
        Map<UUID, Tag> map = new LinkedHashMap<>();
        tagRepository.findAll().forEach(t -> map.put(t.getId(), t));
        return map;
    }

    private Map<UUID, Long> countAgentTags() {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (AgentTag link : agentTagRepository.findAll()) {
            counts.merge(link.getTagId(), 1L, Long::sum);
        }
        return counts;
    }

    private Map<UUID, Long> countKnowledgeBaseTags() {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (KnowledgeBaseTag link : knowledgeBaseTagRepository.findAll()) {
            counts.merge(link.getTagId(), 1L, Long::sum);
        }
        return counts;
    }

    private void apply(Tag tag, TagRequest request, boolean creating) {
        if (request.name() != null || creating) {
            if (!StringUtils.hasText(request.name())) {
                throw ApiException.badRequest("标签名称必填");
            }
            String name = request.name().trim();
            if (name.length() > 64) {
                throw ApiException.badRequest("标签名称过长（上限 64 字符）");
            }
            tagRepository.findByNameIgnoreCase(name)
                    .filter(other -> !other.getId().equals(tag.getId()))
                    .ifPresent(other -> {
                        throw ApiException.conflict("已存在同名标签: " + name);
                    });
            tag.setName(name);
        }
        if (request.sort() != null) {
            tag.setSort(request.sort());
        }
        if (request.enabled() != null) {
            tag.setEnabled(request.enabled());
        }
    }

    private TagView toAdminView(Tag tag, long agentCount, long kbCount) {
        return new TagView(tag.getId(), tag.getName(),
                tag.getSort(), tag.isEnabled(), agentCount, kbCount);
    }
}
