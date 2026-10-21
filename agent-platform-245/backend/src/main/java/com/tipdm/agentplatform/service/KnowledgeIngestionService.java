package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.KnowledgeBaseDocument;
import com.tipdm.agentplatform.domain.KnowledgeDocumentStatus;
import com.tipdm.agentplatform.repository.KnowledgeBaseChunkRepository;
import com.tipdm.agentplatform.repository.KnowledgeBaseDocumentRepository;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * rag 文档异步摄入（P3）：
 *   pending → parsing（读文件 + 解析文本）→ chunking（分块）→ indexing（向量化 + 写库）→ ready
 * 由 {@link KnowledgeBaseService#uploadDocument} 在后台线程触发；状态机落库于 knowledge_base_documents，
 * 任意环节失败置 {@code error} 并记录原因。
 */
@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final KnowledgeBaseDocumentRepository documentRepository;
    private final KnowledgeBaseChunkRepository chunkRepository;
    private final KnowledgeStorageService storageService;
    private final EmbeddingClient embeddingClient;
    private final DocumentTextExtractor textExtractor;

    public KnowledgeIngestionService(
            KnowledgeBaseDocumentRepository documentRepository,
            KnowledgeBaseChunkRepository chunkRepository,
            KnowledgeStorageService storageService,
            EmbeddingClient embeddingClient,
            DocumentTextExtractor textExtractor) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.storageService = storageService;
        this.embeddingClient = embeddingClient;
        this.textExtractor = textExtractor;
    }

    /**
     * 摄入：读取 → 分块 → 向量化 → 写库。
     *
     * <p><b>刻意不加 @Transactional</b>（2026-09-17）：原实现整个方法一个事务，而
     * 「indexing」阶段对<b>每个分块</b>串行调用远程 embedding（数百块 = 连接被占用数分钟），
     * 外部限流/抖动时 DB 连接池被少数几次摄入吃光。现在每步落库各自短事务：
     * 状态推进（parsing→chunking→indexing→ready）逐步可见，失败时由调用方回写 error。
     * 代价是失败可能留下「部分分块 + 非 ready 状态」，检索侧按状态过滤不受影响
     * （重入时会先 deleteByDocumentId 清旧分块，幂等）。</p>
     */
    public void ingest(UUID kbId, UUID docId, int chunkSize, int chunkOverlap) {
        KnowledgeBaseDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + docId));
        if (doc.getKnowledgeBaseId() == null || !doc.getKnowledgeBaseId().equals(kbId)) {
            throw ApiException.badRequest("文档不属于该知识库");
        }

        // parsing：读取文件
        doc.setStatus(KnowledgeDocumentStatus.parsing);
        documentRepository.save(doc);
        byte[] bytes;
        try {
            bytes = storageService.readAllBytes(kbId, doc.getRelativePath());
        } catch (Exception e) {
            throw new IllegalStateException("读取文档文件失败: " + e.getMessage(), e);
        }
        // 解析文本：按真实文件类型抽取（docx/pdf 等二进制格式走 Tika，见 DocumentTextExtractor）。
        // 旧实现直接 new String(bytes, UTF_8)，.docx(ZIP) 解出 0x00 导致 Postgres 拒写。
        String text = textExtractor.extract(doc.getFilename(), bytes);

        // chunking：按该知识库的分块参数切分
        doc.setStatus(KnowledgeDocumentStatus.chunking);
        documentRepository.save(doc);
        KnowledgeChunker chunker = new KnowledgeChunker(chunkSize, chunkOverlap);
        List<String> chunks = chunker.chunk(text);
        if (chunks.isEmpty()) {
            doc.setChunkCount(0);
            doc.setStatus(KnowledgeDocumentStatus.ready);
            documentRepository.save(doc);
            return;
        }

        // indexing：向量化 + 写库（先清旧分块，保证幂等重入）
        doc.setStatus(KnowledgeDocumentStatus.indexing);
        documentRepository.save(doc);
        chunkRepository.deleteByDocumentId(docId);
        int idx = 0;
        for (String chunk : chunks) {
            float[] vec = embeddingClient.embed(chunk);
            chunkRepository.insertChunk(UUID.randomUUID(), docId, kbId, idx, chunk, vec);
            idx++;
        }
        doc.setChunkCount(chunks.size());
        doc.setStatus(KnowledgeDocumentStatus.ready);
        documentRepository.save(doc);
    }

    @Transactional
    public void markError(UUID docId, String message) {
        documentRepository.findById(docId).ifPresent(d -> {
            d.setStatus(KnowledgeDocumentStatus.error);
            d.setError(message != null ? message : "未知错误");
            d.setChunkCount(0);
            documentRepository.save(d);
        });
    }
}
