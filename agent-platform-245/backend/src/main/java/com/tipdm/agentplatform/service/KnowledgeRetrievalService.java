package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.repository.KnowledgeBaseChunkRepository;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.SearchResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * rag 语义检索（P3）：问题向量化 → pgvector 余弦相似度排序 → 归一化为 {@link SearchResult#vector}。
 */
@Service
public class KnowledgeRetrievalService {

    private final EmbeddingClient embeddingClient;
    private final KnowledgeBaseChunkRepository chunkRepository;

    public KnowledgeRetrievalService(EmbeddingClient embeddingClient,
                                     KnowledgeBaseChunkRepository chunkRepository) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
    }

    @Transactional(readOnly = true)
    public List<SearchResult> search(UUID knowledgeBaseId, String query, int topK) {
        float[] q = embeddingClient.embed(query);
        return chunkRepository.search(knowledgeBaseId, q, topK).stream()
                .map(r -> SearchResult.vector(r.documentId(), r.chunkIndex(), r.score(), r.content()))
                .toList();
    }
}
