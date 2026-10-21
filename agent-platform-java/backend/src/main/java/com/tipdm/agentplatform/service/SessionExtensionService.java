package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentSessionExtension;
import com.tipdm.agentplatform.domain.AgentSessionExtensionEntry;
import com.tipdm.agentplatform.repository.AgentSessionExtensionRepository;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.SessionExtensionDtos.ExtensionResponse;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import java.util.ArrayList;
import java.util.List;
import com.tipdm.agentplatform.web.dto.SessionExtensionDtos.EntryItem;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 智能体会话扩展信息服务。
 *
 * <p>管理垂类智能体会话过程中的自定义键值数据（如教材编写智能体的章节数、目录结构等）。</p>
 */
@Service
public class SessionExtensionService {

    private static final Logger log = LoggerFactory.getLogger(SessionExtensionService.class);

    private final AgentSessionExtensionRepository repository;

    public SessionExtensionService(AgentSessionExtensionRepository repository) {
        this.repository = repository;
    }

    /**
     * 获取某会话的全部扩展信息。
     *
     * @throws ApiException 404 若不存在扩展记录
     */
    @Transactional(readOnly = true)
    public ExtensionResponse get(String agentId, String sessionId) {
        AgentSessionExtension ext = repository.findByAgentIdAndSessionId(agentId, sessionId)
                .orElseThrow(() -> ApiException.notFound("会话扩展信息不存在"));
        return toResponse(ext);
    }

    /**
     * 获取某会话的全部扩展信息（不存在时返回 null 而非抛异常）。
     */
    @Transactional(readOnly = true)
    public ExtensionResponse getOrNull(String agentId, String sessionId) {
        return repository.findByAgentIdAndSessionId(agentId, sessionId)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * 批量设置扩展条目（覆盖已有 key，新增不存在的 key）。
     */
    @Transactional
    public ExtensionResponse upsertEntries(String agentId, String sessionId, String userId,
                                           List<EntryItem> entries) {
        if (entries == null || entries.isEmpty()) {
            throw ApiException.badRequest("扩展条目不能为空");
        }
        AgentSessionExtension ext = repository.findByAgentIdAndSessionId(agentId, sessionId)
                .map(existing -> {
                    // 已有记录归属他人时按「不存在」处理，避免凭 sessionId 往他人会话注入条目
                    if (existing.getUserId() != null && !existing.getUserId().equals(userId)) {
                        throw ApiException.notFound("会话不存在");
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    AgentSessionExtension created = new AgentSessionExtension();
                    created.setAgentId(agentId);
                    created.setSessionId(sessionId);
                    created.setUserId(userId);
                    return created;
                });
        for (EntryItem item : entries) {
            if (item.key() == null || item.key().isBlank()) {
                throw ApiException.badRequest("扩展条目 key 不能为空");
            }
            ext.putEntry(item.key(), item.value());
        }
        repository.save(ext);
        log.debug("Upserted {} extension entries for agent={}, session={}", entries.size(), agentId, sessionId);
        return toResponse(ext);
    }

    /**
     * 设置单个扩展条目。
     */
    @Transactional
    public ExtensionResponse putEntry(String agentId, String sessionId, String userId,
                                      String key, String value) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("扩展条目 key 不能为空");
        }
        AgentSessionExtension ext = repository.findByAgentIdAndSessionId(agentId, sessionId)
                .map(existing -> {
                    // 同 upsertEntries：已有记录归属他人时按「不存在」处理
                    if (existing.getUserId() != null && !existing.getUserId().equals(userId)) {
                        throw ApiException.notFound("会话不存在");
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    AgentSessionExtension created = new AgentSessionExtension();
                    created.setAgentId(agentId);
                    created.setSessionId(sessionId);
                    created.setUserId(userId);
                    return created;
                });
        ext.putEntry(key, value);
        repository.save(ext);
        log.debug("Put extension entry key={} for agent={}, session={}", key, agentId, sessionId);
        return toResponse(ext);
    }

    /**
     * 删除单个扩展条目。
     */
    @Transactional
    public void deleteEntry(String agentId, String sessionId, String key) {
        AgentSessionExtension ext = repository.findByAgentIdAndSessionId(agentId, sessionId)
                .orElseThrow(() -> ApiException.notFound("会话扩展信息不存在"));
        ext.removeEntry(key);
        repository.save(ext);
        log.debug("Removed extension entry key={} for agent={}, session={}", key, agentId, sessionId);
    }

    /**
     * 删除整个会话的扩展信息。
     */
    @Transactional
    public void deleteAll(String agentId, String sessionId) {
        repository.deleteByAgentIdAndSessionId(agentId, sessionId);
        log.debug("Deleted all extension entries for agent={}, session={}", agentId, sessionId);
    }

    /**
     * 删除会话的扩展信息（不依赖 agentId；会话清理兜底链路用）。
     */
    @Transactional
    public void deleteBySessionId(String sessionId) {
        repository.deleteBySessionId(sessionId);
    }

    /**
     * 删除智能体下所有会话的扩展信息。
     */
    @Transactional
    public void deleteByAgent(String agentId) {
        repository.deleteByAgentId(agentId);
    }

    /**
     * 教材库列表：分页返回某智能体下当前用户新建的全部教材（新建/进行中的一并列出），
     * 支持按书名关键字模糊搜索，用于前端无限滚动加载。
     *
     * <p>每项携带该教材扩展信息中的全部字段（定位字段与 {@code form.*} 详情）。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<ExtensionResponse> searchByEntries(
            String agentId,
            String userId,                 // 归属过滤：只能检索自己的会话
            Map<String, String> likeMap,   // 模糊(前缀)
            Map<String, String> eqMap,     // 精确
            int page, int size) {

        Specification<AgentSessionExtension> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("agentId"), agentId));
            // 原实现只按 agentId 过滤，任何人凭 agentId + 构造的 like.* 即可
            // 分页拉取他人会话的扩展条目（含正文内容），属越权读。
            if (userId != null && !userId.isBlank()) {
                preds.add(cb.equal(root.get("userId"), userId));
            }
            likeMap.forEach((k, v) -> {
                if (hasText(v)) {
                    preds.add(entryLike(cb, query, root, k, v.trim().toLowerCase()));
                }
            });
            eqMap.forEach((k, v) -> {
                if (hasText(v)) {
                    preds.add(entryEq(cb, query, root, k, v.trim()));
                }
            });
            return cb.and(preds.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<AgentSessionExtension> result = repository.findAll(spec, pageable);
        List<ExtensionResponse> items = result.getContent().stream().map(this::toResponse).toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }


    /** 模糊匹配：存在某条 entry 的 key 等于 key，且 value 以 prefix 开头 */
    private static Predicate entryLike(CriteriaBuilder cb, CriteriaQuery<?> query,
                                       Root<AgentSessionExtension> root, String key, String prefix) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<AgentSessionExtensionEntry> e = sub.from(AgentSessionExtensionEntry.class);
        sub.select(e.get("id"));
        sub.where(cb.and(
                cb.equal(e.get("extension"), root),
                cb.equal(e.get("entryKey"), key),
                cb.like(cb.lower(e.get("entryValue")), prefix + "%")));
        return cb.exists(sub);
    }

    /** 精确匹配：存在某条 entry 的 key 等于 key，且 value 完全等于 value */
    private static Predicate entryEq(CriteriaBuilder cb, CriteriaQuery<?> query,
                                     Root<AgentSessionExtension> root, String key, String value) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<AgentSessionExtensionEntry> e = sub.from(AgentSessionExtensionEntry.class);
        sub.select(e.get("id"));
        sub.where(cb.and(
                cb.equal(e.get("extension"), root),
                cb.equal(e.get("entryKey"), key),
                cb.equal(e.get("entryValue"), value)));
        return cb.exists(sub);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }


    private ExtensionResponse toResponse(AgentSessionExtension ext) {
        List<EntryItem> items = ext.getEntries().stream()
                .map(e -> new EntryItem(e.getEntryKey(), e.getEntryValue()))
                .toList();
        return new ExtensionResponse(
                ext.getId(),
                ext.getAgentId(),
                ext.getSessionId(),
                ext.getUserId(),
                items,
                ext.getCreatedAt(),
                ext.getUpdatedAt());
    }
}
