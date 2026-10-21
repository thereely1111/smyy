package com.tipdm.agentplatform.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 知识库分块向量表（V24 {@code knowledge_base_chunks}）的访问层。
 * <p>采用原生 SQL 而非 JPA 实体，规避 Hibernate 对 {@code vector(1536)} 类型的 ddl-auto:validate 校验冲突；
 * 向量以 {@code [v0,v1,...]} 字面量经 {@code CAST(? AS vector)} 写入/查询，由 pgvector 扩展解析。
 */
@Repository
public class KnowledgeBaseChunkRepository {

    @PersistenceContext
    private EntityManager em;

    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    @Transactional
    public void insertChunk(UUID id, UUID documentId, UUID knowledgeBaseId,
                            int chunkIndex, String content, float[] embedding) {
        String sql = "INSERT INTO knowledge_base_chunks "
                + "(id, document_id, knowledge_base_id, chunk_index, content, embedding, "
                + "metadata, created_at, updated_at) "
                + "VALUES (:id, :docId, :kbId, :idx, :content, CAST(:emb AS vector), NULL, now(), now())";
        em.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("docId", documentId)
                .setParameter("kbId", knowledgeBaseId)
                .setParameter("idx", chunkIndex)
                .setParameter("content", content)
                .setParameter("emb", toVectorLiteral(embedding))
                .executeUpdate();
    }

    @Transactional
    public void deleteByDocumentId(UUID documentId) {
        em.createNativeQuery("DELETE FROM knowledge_base_chunks WHERE document_id = :docId")
                .setParameter("docId", documentId)
                .executeUpdate();
    }

    @Transactional
    public void deleteByKnowledgeBaseId(UUID knowledgeBaseId) {
        em.createNativeQuery("DELETE FROM knowledge_base_chunks WHERE knowledge_base_id = :kbId")
                .setParameter("kbId", knowledgeBaseId)
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<VectorSearchRow> search(UUID knowledgeBaseId, float[] query, int topK) {
        String sql = "SELECT document_id, chunk_index, 1 - (embedding <=> CAST(:q AS vector)) AS score, content "
                + "FROM knowledge_base_chunks "
                + "WHERE knowledge_base_id = :kbId "
                + "ORDER BY embedding <=> CAST(:q AS vector) "
                + "LIMIT :k";
        List<?> rows = em.createNativeQuery(sql)
                .setParameter("kbId", knowledgeBaseId)
                .setParameter("q", toVectorLiteral(query))
                .setParameter("k", topK)
                .getResultList();
        List<VectorSearchRow> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            out.add(new VectorSearchRow(
                    (UUID) row[0],
                    ((Number) row[1]).intValue(),
                    ((Number) row[2]).doubleValue(),
                    (String) row[3]));
        }
        return out;
    }

    public record VectorSearchRow(UUID documentId, int chunkIndex, double score, String content) {
    }
}
