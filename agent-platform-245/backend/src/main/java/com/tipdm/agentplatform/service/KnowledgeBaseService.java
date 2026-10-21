package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.ExternalProvider;
import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.Group;
import com.tipdm.agentplatform.domain.KnowledgeBase;
import com.tipdm.agentplatform.domain.KnowledgeBaseDocument;
import com.tipdm.agentplatform.domain.KnowledgeBaseGrant;
import com.tipdm.agentplatform.domain.KnowledgeBasePermission;
import com.tipdm.agentplatform.domain.KnowledgeBaseScope;
import com.tipdm.agentplatform.domain.KnowledgeBaseType;
import com.tipdm.agentplatform.domain.KnowledgeDocumentStatus;
import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.domain.UserGroup;
import com.tipdm.agentplatform.repository.KnowledgeBaseDocumentRepository;
import com.tipdm.agentplatform.repository.KnowledgeBaseGrantRepository;
import com.tipdm.agentplatform.repository.KnowledgeBaseRepository;
import com.tipdm.agentplatform.repository.GroupRepository;
import com.tipdm.agentplatform.repository.UserGroupRepository;
import com.tipdm.agentplatform.repository.UserRepository;
import com.tipdm.agentplatform.web.dto.GroupDtos.GroupSummary;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.GrantRequest;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.GrantView;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.KnowledgeBaseView;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.KnowledgeDocumentStatusView;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.KnowledgeDocumentView;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.ListGrantsResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.ListKnowledgeDocumentStatusResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.ListKnowledgeDocumentsResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.ListKnowledgeBasesResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.OwnerView;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.CreateKnowledgeBaseRequest;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.UpdateKnowledgeBaseRequest;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.UploadKnowledgeDocumentResponse;
import com.tipdm.agentplatform.web.dto.TagDtos.TagView;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.annotation.PreDestroy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.SearchKnowledgeBaseResponse;

/**
 * 知识库业务编排（P1 核心 CRUD + P2 授权与可见性）。
 * <p>授权模型（角色判定统一在接口层完成，见 {@link Viewer}；本类只做资源级 ACL）：
 * <ul>
 *   <li>owner 与管理端视角（viewer.admin，接口层按 admin:access 权限码裁决）
 *       对所有知识库拥有 WRITE；</li>
 *   <li>显式授权 knowledge_base_grants 记录 USER/GROUP 的 READ/WRITE；</li>
 *   <li>群组展开：有效权限 = 直接授权 ∪ 所属群组授权（取最大 WRITE &gt; READ）；</li>
 *   <li>editable = 有效权限为 WRITE；列表仅返回有 READ 及以上权限的知识库
 *       （管理端视角返回全部）；</li>
 *   <li>授权管理（增删查）仅限 owner/admin；external 连接配置仅对 owner/admin 可见。</li>
 * </ul>
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** RAG 分块默认参数，与 {@link KnowledgeBase#DEFAULT_CHUNK_SIZE} 保持一致。 */
    private static final int DEFAULT_CHUNK_SIZE = KnowledgeBase.DEFAULT_CHUNK_SIZE;
    private static final int DEFAULT_CHUNK_OVERLAP = KnowledgeBase.DEFAULT_CHUNK_OVERLAP;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseDocumentRepository documentRepository;
    private final KnowledgeBaseGrantRepository grantRepository;
    private final UserGroupRepository userGroupRepository;
    private final KnowledgeStorageService storageService;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupService groupService;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeTextRetrievalService textRetrievalService;
    private final ExternalApiKeyCipher externalApiKeyCipher;
    private final ExternalRagClient externalRagClient;
    private final TagService tagService;

    @PersistenceContext
    private EntityManager em;

    public KnowledgeBaseService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeBaseDocumentRepository documentRepository,
            KnowledgeBaseGrantRepository grantRepository,
            UserGroupRepository userGroupRepository,
            KnowledgeStorageService storageService,
            UserRepository userRepository,
            GroupRepository groupRepository,
            GroupService groupService,
            KnowledgeIngestionService ingestionService,
            KnowledgeRetrievalService retrievalService,
            KnowledgeTextRetrievalService textRetrievalService,
            ExternalApiKeyCipher externalApiKeyCipher,
            ExternalRagClient externalRagClient,
            TagService tagService) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.grantRepository = grantRepository;
        this.userGroupRepository = userGroupRepository;
        this.storageService = storageService;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.groupService = groupService;
        this.ingestionService = ingestionService;
        this.retrievalService = retrievalService;
        this.textRetrievalService = textRetrievalService;
        this.externalApiKeyCipher = externalApiKeyCipher;
        this.externalRagClient = externalRagClient;
        this.tagService = tagService;
    }

    /** 个人库创建：scope 固定 PERSONAL，任何登录用户可建（门槛在路由，不在请求字段）。 */
    @Transactional
    public KnowledgeBaseView create(CreateKnowledgeBaseRequest request, Viewer viewer) {
        return doCreate(request, viewer, KnowledgeBaseScope.PERSONAL);
    }

    /**
     * 平台库创建：scope 固定 PLATFORM；knowledge:create 门槛由接口层把门。
     * （补 @Transactional：个人库 create 有而本方法一直缺失——/platform 端点
     * 此前无前端入口、从未被真实调用，新建按钮上线后首次执行 persist 即抛
     * TransactionRequiredException。）
     */
    @Transactional
    public KnowledgeBaseView createPlatform(CreateKnowledgeBaseRequest request, Viewer viewer) {
        return doCreate(request, viewer, KnowledgeBaseScope.PLATFORM);
    }

    private KnowledgeBaseView doCreate(
            CreateKnowledgeBaseRequest request, Viewer viewer, KnowledgeBaseScope scope) {
        if (request.type() == null) {
            throw ApiException.badRequest("必须指定知识库类型（rag / external / text）");
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(UUID.randomUUID());
        kb.setName(request.name());
        kb.setDescription(request.description());
        kb.setType(request.type());
        // 归属范围由路由决定，不经请求体（防变造）；update 链路不触碰该字段
        kb.setScope(scope);
        kb.setOwnerUserId(viewer.userId());

        switch (request.type()) {
            case rag -> {
                if (request.embeddingModelConfig() == null) {
                    throw ApiException.badRequest("rag 类型必须指定 embeddingModelConfig");
                }
                kb.setEmbeddingModelConfig(request.embeddingModelConfig());
                // storage_root_rel 必须在插入前设置以满足 CHECK 约束（rag/text 要求非空）
                kb.setStorageRootRel(kb.getId().toString());
                // 分块参数：未提供则用默认值，重叠收敛到 size/2 以内
                int size = request.chunkSize() != null ? request.chunkSize() : DEFAULT_CHUNK_SIZE;
                int overlap = request.chunkOverlap() != null ? request.chunkOverlap() : DEFAULT_CHUNK_OVERLAP;
                kb.setChunkSize(size);
                kb.setChunkOverlap(clampOverlap(size, overlap));
            }
            case external -> {
                if (request.externalProvider() == null || isBlank(request.externalEndpoint())) {
                    throw ApiException.badRequest("external 类型必须指定 externalProvider 与 externalEndpoint");
                }
                kb.setExternalProvider(request.externalProvider());
                kb.setExternalEndpoint(request.externalEndpoint());
                kb.setExternalApiKeyEnc(externalApiKeyCipher.encrypt(request.externalApiKey()));
                kb.setExternalExtra(request.externalExtra());
            }
            case text -> kb.setStorageRootRel(kb.getId().toString());
            default -> throw new IllegalStateException("未知知识库类型: " + request.type());
        }
        // 手动分配 UUID + persist：save() 会因 id 非空走 merge 导致乐观锁异常，
        // 且 rag/text 的 storage_root_rel 必须在插入时即非空以满足 CHECK 约束。
        em.persist(kb);
        if (request.tagIds() != null) {
            tagService.setKnowledgeBaseTags(kb.getId(), request.tagIds());
        }
        return toView(kb, viewer);
    }

    public KnowledgeBaseView get(UUID id, Viewer viewer) {
        KnowledgeBase kb = requireVisible(id, viewer);
        return toView(kb, viewer);
    }

    public ListKnowledgeBasesResponse list(Viewer viewer, UUID tag, KnowledgeBaseScope scope) {
        requireUser(viewer.userId());
        List<KnowledgeBase> all;
        if (viewer.admin()) {
            all = knowledgeBaseRepository.findAll();
        } else {
            // 可见性 = owner ∪ 直接授权 ∪ 群组授权
            Set<UUID> ids = new HashSet<>();
            knowledgeBaseRepository.findByOwnerUserId(viewer.userId())
                    .forEach(kb -> ids.add(kb.getId()));
            grantRepository
                    .findByPrincipalTypeAndPrincipalId(GrantPrincipalType.USER, viewer.userId())
                    .forEach(g -> ids.add(g.getKnowledgeBaseId()));
            Set<UUID> myGroups = userGroupRepository.findByUserId(viewer.userId()).stream()
                    .map(UserGroup::getGroupId)
                    .collect(Collectors.toSet());
            if (!myGroups.isEmpty()) {
                grantRepository
                        .findByPrincipalTypeAndPrincipalIdIn(
                                GrantPrincipalType.GROUP, new ArrayList<>(myGroups))
                        .forEach(g -> ids.add(g.getKnowledgeBaseId()));
            }
            all = knowledgeBaseRepository.findAllById(ids);
        }
        // 归属范围筛选：先按可见性，再按 scope（PLATFORM / PERSONAL）
        if (scope != null) {
            all = all.stream().filter(kb -> kb.getScope() == scope).toList();
        }
        // 标签筛选：先按可见性，再取与标签挂载集的交集
        if (tag != null) {
            Set<UUID> tagged = tagService.knowledgeBaseIdsByTag(tag);
            all = all.stream().filter(kb -> tagged.contains(kb.getId())).toList();
        }
        // 批量拼标签，避免逐行 N+1
        Map<UUID, List<TagView>> tagsByKb = tagService.tagsByKnowledgeBaseIds(
                all.stream().map(KnowledgeBase::getId).toList());
        List<KnowledgeBaseView> views = all.stream()
                .map(kb -> toView(kb, viewer, tagsByKb))
                .toList();
        return new ListKnowledgeBasesResponse(views, views.size());
    }

    @Transactional
    public KnowledgeBaseView update(UUID id, UpdateKnowledgeBaseRequest request, Viewer viewer) {
        KnowledgeBase kb = requireWritable(id, viewer);
        if (request.name() != null) {
            kb.setName(request.name());
        }
        if (request.description() != null) {
            kb.setDescription(request.description());
        }
        if (request.embeddingModelConfig() != null) {
            if (kb.getType() != KnowledgeBaseType.rag) {
                throw ApiException.badRequest("仅 rag 类型可设置 embedding_model_config");
            }
            // 读取路径把密钥字段脱敏成 "***"，若用户保存时原样回传，真密钥会被掩码覆盖。
            // 这里把"掩码/空"的密钥字段替换回库里的原值（语义与 externalApiKey 一致）。
            kb.setEmbeddingModelConfig(
                    mergeEmbeddingConfigSecrets(kb.getEmbeddingModelConfig(),
                            request.embeddingModelConfig()));
        }
        if (request.externalProvider() != null || request.externalEndpoint() != null
                || request.externalExtra() != null || request.externalApiKey() != null) {
            if (kb.getType() != KnowledgeBaseType.external) {
                throw ApiException.badRequest("仅 external 类型可设置外部 RAG 配置");
            }
            if (request.externalProvider() != null) {
                kb.setExternalProvider(request.externalProvider());
            }
            if (request.externalEndpoint() != null) {
                kb.setExternalEndpoint(request.externalEndpoint());
            }
            if (request.externalApiKey() != null) {
                // 仅当本次显式传入密钥才重加密；未传则保留既有加密值
                kb.setExternalApiKeyEnc(externalApiKeyCipher.encrypt(request.externalApiKey()));
            }
            if (request.externalExtra() != null) {
                kb.setExternalExtra(request.externalExtra());
            }
        }
        if (request.chunkSize() != null || request.chunkOverlap() != null) {
            if (kb.getType() != KnowledgeBaseType.rag) {
                throw ApiException.badRequest("仅 rag 类型可设置分块参数");
            }
            int size = request.chunkSize() != null
                    ? request.chunkSize()
                    : (kb.getChunkSize() != null ? kb.getChunkSize() : DEFAULT_CHUNK_SIZE);
            int overlap = request.chunkOverlap() != null
                    ? request.chunkOverlap()
                    : (kb.getChunkOverlap() != null ? kb.getChunkOverlap() : DEFAULT_CHUNK_OVERLAP);
            kb.setChunkSize(size);
            kb.setChunkOverlap(clampOverlap(size, overlap));
        }
        validateTypeConstraints(kb);
        knowledgeBaseRepository.save(kb);
        return toView(kb, viewer);
    }

    @Transactional
    public void delete(UUID id, Viewer viewer) {
        KnowledgeBase kb = requireWritable(id, viewer);
        storageService.deleteKnowledgeBase(kb.getId());
        grantRepository.deleteByKnowledgeBaseId(kb.getId());
        documentRepository.deleteByKnowledgeBaseId(kb.getId());
        knowledgeBaseRepository.delete(kb);
    }

    @Transactional
    public UploadKnowledgeDocumentResponse uploadDocument(
            UUID kbId, String filename, InputStream content, Viewer viewer) {
        KnowledgeBase kb = requireWritable(kbId, viewer);
        if (kb.getType() == KnowledgeBaseType.external) {
            throw ApiException.badRequest("外部知识库文档由外部平台托管，不可上传文件");
        }
        KnowledgeBaseDocument doc = new KnowledgeBaseDocument();
        doc.setId(UUID.randomUUID());
        doc.setKnowledgeBaseId(kbId);
        doc.setFilename(filename);
        // rag 走异步向量管道（P3），text 上传即就绪
        doc.setStatus(kb.getType() == KnowledgeBaseType.rag
                ? KnowledgeDocumentStatus.pending
                : KnowledgeDocumentStatus.ready);
        // 用文档 id 作为存储子目录，先落盘再写入相对路径，最后 persist
        String relativePath = storageService.store(kbId, doc.getId(), filename, content);
        doc.setRelativePath(relativePath);
        em.persist(doc);
        UUID docId = doc.getId();
        // rag 类型走异步向量管道；必须在当前事务提交后再触发，
        // 否则摄入线程可能在文档落库前读取，导致「文档不存在」。
        if (kb.getType() == KnowledgeBaseType.rag) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            scheduleIngestion(kbId, docId);
                        }
                    });
        }
        return new UploadKnowledgeDocumentResponse(docId, filename, doc.getStatus());
    }

    public ListKnowledgeDocumentStatusResponse getDocumentStatus(UUID kbId, List<UUID> ids, Viewer viewer) {
        requireVisible(kbId, viewer);
        List<KnowledgeBaseDocument> docs = documentRepository.findByKnowledgeBaseId(kbId);
        if (ids != null && !ids.isEmpty()) {
            docs = docs.stream().filter(d -> ids.contains(d.getId())).toList();
        }
        return new ListKnowledgeDocumentStatusResponse(docs.stream()
                .map(d -> new KnowledgeDocumentStatusView(
                        d.getId(), d.getStatus(), d.getChunkCount(), d.getError()))
                .toList());
    }

    /**
     * 文档清单：按创建时间倒序，最近上传的排在最前。
     *
     * <p>大小与 MIME 需要读取落盘文件；文件缺失（如存储被手工清理）时大小记 0、
     * 类型回退为扩展名推断值，不因单个文件异常导致整个清单失败。
     */
    public ListKnowledgeDocumentsResponse listDocuments(UUID kbId, Viewer viewer) {
        requireVisible(kbId, viewer);
        List<KnowledgeDocumentView> views = documentRepository.findByKnowledgeBaseId(kbId).stream()
                .sorted(Comparator.comparing(
                        KnowledgeBaseDocument::getCreatedAt,
                        Comparator.nullsLast(Comparator.<Instant>reverseOrder())))
                .map(this::toDocumentView)
                .toList();
        return new ListKnowledgeDocumentsResponse(views, views.size());
    }

    private KnowledgeDocumentView toDocumentView(KnowledgeBaseDocument doc) {
        long size = 0;
        String contentType = null;
        try {
            Path path = storageService.resolve(doc.getKnowledgeBaseId(), doc.getRelativePath());
            if (Files.isRegularFile(path)) {
                size = Files.size(path);
                contentType = Files.probeContentType(path);
            }
        } catch (IOException | ApiException e) {
            // 单个文档的元信息读不到不该让整个清单失败：退化为 size=0 + 按扩展名推断的类型
            log.warn("读取知识库文档元信息失败 kbId={} docId={}: {}",
                    doc.getKnowledgeBaseId(), doc.getId(), e.getMessage());
        }
        if (contentType == null) {
            contentType = guessContentType(doc.getFilename());
        }
        return new KnowledgeDocumentView(
                doc.getId(),
                doc.getFilename(),
                size,
                contentType,
                doc.getStatus(),
                doc.getError(),
                doc.getChunkCount(),
                doc.getCreatedAt(),
                doc.getUpdatedAt());
    }

    /**
     * 按扩展名兜底推断 MIME —— JDK 的 {@link Files#probeContentType} 在缺少
     * 文件类型探测器的环境下常返回 null，前端 accept 属性依赖该字段。
     */
    private static String guessContentType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "txt", "md", "markdown", "log" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "html", "htm" -> "text/html";
            case "xml" -> "application/xml";
            case "yaml", "yml" -> "application/yaml";
            case "doc" -> "application/msword";
            case "docx" ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }

    @Transactional
    public void deleteDocument(UUID kbId, UUID docId, Viewer viewer) {
        requireWritable(kbId, viewer);
        KnowledgeBaseDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在"));
        if (!doc.getKnowledgeBaseId().equals(kbId)) {
            throw ApiException.badRequest("文档不属于该知识库");
        }
        storageService.deleteDocument(kbId, doc.getRelativePath());
        documentRepository.delete(doc);
    }

    // ---- 检索（P3 rag / P3.5 text / P4 external） ----

    /**
     * 统一检索入口，按 KB 类型分派：
     * <ul>
     *   <li>rag：向量语义检索（pgvector）；</li>
     *   <li>text：服务端 grep 兜底（目录内逐行匹配）；</li>
     *   <li>external：委托外部 RAG 平台（RAGFlow / Dify / 百炼）。</li>
     * </ul>
     */
    public SearchKnowledgeBaseResponse search(
            UUID id, String query, Integer topK, Viewer viewer) {
        KnowledgeBase kb = requireVisible(id, viewer);
        int k = (topK == null || topK <= 0) ? 5 : Math.min(topK, 50);
        return switch (kb.getType()) {
            case rag -> new SearchKnowledgeBaseResponse(
                    KnowledgeBaseType.rag, retrievalService.search(id, query, k));
            case text -> new SearchKnowledgeBaseResponse(
                    KnowledgeBaseType.text, textRetrievalService.search(id, query, k));
            case external -> new SearchKnowledgeBaseResponse(
                    KnowledgeBaseType.external, externalRagClient.search(kb, query, k));
        };
    }

    /**
     * agent 运行时挂载 KB 的钩子（P3.5 主路径）：返回该 KB 的绝对目录，
     * 供运行时把 grep / cat 工具的作用域限定在此目录（防穿越守卫由
     * {@link KnowledgeStorageService#kbDir} 保证）。READ 及以上权限方可获取。
     */
    public Path resolveStorageDir(UUID kbId, Viewer viewer) {
        requireVisible(kbId, viewer);
        return storageService.kbDir(kbId);
    }

    /**
     * 文档摄入专用线程池（2 线程，有界）。
     *
     * <p><b>为什么不用 {@code CompletableFuture.runAsync}</b>（2026-09-17）：它落在
     * {@code ForkJoinPool.commonPool()} 上，而摄入是纯阻塞 IO（远程 embedding + 多次
     * DB 写、串行、可达数分钟）。几个文档同时摄入就把公共池打满，连带拖垮所有跑在
     * 公共池 / {@code Schedulers.parallel()} 上的平台异步任务（它们共用同一批 worker）。</p>
     */
    private final ExecutorService ingestionExecutor =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "kb-ingestion");
                thread.setDaemon(true);
                return thread;
            });

    @PreDestroy
    void shutdownIngestionExecutor() {
        ingestionExecutor.shutdown();
    }

    /** 后台线程触发 rag 文档摄入；异常时由摄入服务回写 error 状态。 */
    private void scheduleIngestion(UUID kbId, UUID docId) {
        ingestionExecutor.submit(() -> {
            try {
                KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                        .orElseThrow(() -> ApiException.notFound("知识库不存在: " + kbId));
                int size = kb.getChunkSize() != null ? kb.getChunkSize() : DEFAULT_CHUNK_SIZE;
                int overlap = kb.getChunkOverlap() != null ? kb.getChunkOverlap() : DEFAULT_CHUNK_OVERLAP;
                ingestionService.ingest(kbId, docId, size, overlap);
            } catch (Exception e) {
                log.error("rag 文档摄入失败 kb={} doc={}: {}", kbId, docId, e.getMessage());
                try {
                    ingestionService.markError(docId, e.getMessage());
                } catch (Exception ex) {
                    log.error("标记摄入错误失败 doc={}: {}", docId, ex.getMessage());
                }
            }
        });
    }

    // ---- 授权（P2） ----

    @Transactional
    public ListGrantsResponse listGrants(UUID kbId, Viewer viewer) {
        KnowledgeBase kb = requireOwnerOrAdmin(kbId, viewer);
        List<GrantView> grants = grantRepository.findByKnowledgeBaseId(kbId).stream()
                .map(g -> toGrantView(g))
                .toList();
        List<GroupSummary> groups = groupService.summariesForGranting(viewer);
        return new ListGrantsResponse(grants, groups);
    }

    @Transactional
    public GrantView createGrant(UUID kbId, GrantRequest request, Viewer viewer) {
        KnowledgeBase kb = requireOwnerOrAdmin(kbId, viewer);
        validatePrincipal(request.principalType(), request.principalId());

        Optional<KnowledgeBaseGrant> existing = grantRepository
                .findByKnowledgeBaseIdAndPrincipalTypeAndPrincipalId(
                        kbId, request.principalType(), request.principalId());
        KnowledgeBaseGrant grant;
        if (existing.isPresent()) {
            // 同一主体仅一条授权：权限取最大（避免降级）
            grant = existing.get();
            grant.setPermission(max(grant.getPermission(), request.permission()));
        } else {
            grant = new KnowledgeBaseGrant();
            grant.setId(UUID.randomUUID());
            grant.setKnowledgeBaseId(kbId);
            grant.setPrincipalType(request.principalType());
            grant.setPrincipalId(request.principalId());
            grant.setPermission(request.permission());
            grant.setGrantedBy(viewer.userId());
            em.persist(grant);
        }
        return toGrantView(grant);
    }

    @Transactional
    public void deleteGrant(UUID kbId, UUID grantId, Viewer viewer) {
        requireOwnerOrAdmin(kbId, viewer);
        KnowledgeBaseGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> ApiException.notFound("授权不存在"));
        if (!grant.getKnowledgeBaseId().equals(kbId)) {
            throw ApiException.badRequest("授权不属于该知识库");
        }
        grantRepository.delete(grant);
    }

    // ---- 内部辅助 ----

    /**
     * 解析某用户对某知识库的有效权限（WRITE > READ；无授权返回 null 表示 NONE）。
     * 管理端视角（viewer.admin，接口层已按权限码裁决）恒为 WRITE。
     */
    private KnowledgeBasePermission resolvePermission(KnowledgeBase kb, Viewer viewer) {
        requireUser(viewer.userId());
        if (viewer.admin() || kb.getOwnerUserId().equals(viewer.userId())) {
            return KnowledgeBasePermission.WRITE;
        }
        Set<UUID> myGroups = userGroupRepository.findByUserId(viewer.userId()).stream()
                .map(UserGroup::getGroupId)
                .collect(Collectors.toSet());
        KnowledgeBasePermission best = null;
        for (KnowledgeBaseGrant g : grantRepository.findByKnowledgeBaseId(kb.getId())) {
            if (g.getPrincipalType() == GrantPrincipalType.USER
                    && g.getPrincipalId().equals(viewer.userId())) {
                best = max(best, g.getPermission());
            } else if (g.getPrincipalType() == GrantPrincipalType.GROUP
                    && myGroups.contains(g.getPrincipalId())) {
                best = max(best, g.getPermission());
            }
        }
        return best;
    }

    private static KnowledgeBasePermission max(KnowledgeBasePermission a, KnowledgeBasePermission b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return (a == KnowledgeBasePermission.WRITE || b == KnowledgeBasePermission.WRITE)
                ? KnowledgeBasePermission.WRITE
                : KnowledgeBasePermission.READ;
    }

    private void requirePermission(KnowledgeBase kb, Viewer viewer, KnowledgeBasePermission min) {
        KnowledgeBasePermission p = resolvePermission(kb, viewer);
        boolean ok = p != null && (min == KnowledgeBasePermission.READ || p == KnowledgeBasePermission.WRITE);
        if (!ok) {
            throw ApiException.forbidden("无权执行该操作");
        }
    }

    /**
     * 至少 READ 权限（owner / 管理端视角 / 任意授权）；public 供 Controller 层
     * 做「平台库按 scope 分流权限码」的实体探查（与 SkillService.requireVisible 同模式）。
     */
    public KnowledgeBase requireVisible(UUID id, Viewer viewer) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("知识库不存在"));
        if (resolvePermission(kb, viewer) == null) {
            throw ApiException.forbidden("无权访问该知识库");
        }
        return kb;
    }

    /** 可写权限（owner / 管理端视角 / WRITE 授权）。 */
    private KnowledgeBase requireWritable(UUID id, Viewer viewer) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("知识库不存在"));
        requirePermission(kb, viewer, KnowledgeBasePermission.WRITE);
        return kb;
    }

    /** 仅 owner / 管理端视角可管理授权与群组。 */
    private KnowledgeBase requireOwnerOrAdmin(UUID id, Viewer viewer) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("知识库不存在"));
        requireUser(viewer.userId());
        if (!viewer.admin() && !kb.getOwnerUserId().equals(viewer.userId())) {
            throw ApiException.forbidden("仅知识库拥有者或管理员可管理授权");
        }
        return kb;
    }

    private void validatePrincipal(GrantPrincipalType type, UUID principalId) {
        if (type == GrantPrincipalType.USER) {
            if (!userRepository.existsById(principalId)) {
                throw ApiException.notFound("用户不存在");
            }
        } else {
            if (!groupRepository.existsById(principalId)) {
                throw ApiException.notFound("群组不存在");
            }
        }
    }

    private User requireUser(UUID currentUserId) {
        return userRepository.findById(currentUserId)
                .orElseThrow(() -> ApiException.unauthorized("用户不存在或已失效"));
    }

    /**
     * 出参脱敏：embedding 配置里可能带着访问大模型的 apiKey，而知识库可以授权给别人——
     * 直接回显等于把创建者的密钥随授权一起送出去（与 externalApiKey「不回显」的口径对齐）。
     * 含 key/secret/token/password 的字段名统一替换为掩码，让前端仍能显示「已配置」。
     */
    private static Map<String, Object> redactEmbeddingConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return config;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            String lower = key == null ? "" : key.toLowerCase();
            boolean secret = lower.contains("key") || lower.contains("secret")
                    || lower.contains("token") || lower.contains("password");
            if (secret) {
                out.put(key, (value instanceof String s && s.isBlank()) ? "" : "***");
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    /**
     * 保存时把「掩码/空」的密钥字段还原成库里的原值。
     *
     * <p>读取路径已把 apiKey 之类脱敏成 {@code "***"}；若用户没改这一栏就保存，
     * 原样回传会把真密钥覆盖成掩码。这里只对含 key/secret/token/password 的字段做
     * 「掩码或空 → 沿用旧值」，其余字段以请求为准（含删除非密钥字段的意图）。</p>
     */
    private static Map<String, Object> mergeEmbeddingConfigSecrets(
            Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(incoming);
        if (existing == null || existing.isEmpty()) {
            return merged;
        }
        merged.forEach((key, value) -> {
            String lower = key == null ? "" : key.toLowerCase();
            boolean secret = lower.contains("key") || lower.contains("secret")
                    || lower.contains("token") || lower.contains("password");
            boolean placeholder = value == null
                    || (value instanceof String s && (s.isBlank() || "***".equals(s)));
            if (secret && placeholder && existing.containsKey(key)) {
                merged.put(key, existing.get(key));
            }
        });
        return merged;
    }

    private void validateTypeConstraints(KnowledgeBase kb) {
        switch (kb.getType()) {
            case rag -> {
                if (kb.getEmbeddingModelConfig() == null || kb.getStorageRootRel() == null) {
                    throw ApiException.badRequest("rag 类型必须包含 embedding_model_config 与存储目录");
                }
            }
            case external -> {
                if (kb.getExternalProvider() == null || isBlank(kb.getExternalEndpoint())) {
                    throw ApiException.badRequest("external 类型必须包含 external_provider 与 external_endpoint");
                }
            }
            case text -> {
                if (kb.getStorageRootRel() == null) {
                    throw ApiException.badRequest("text 类型必须包含存储目录");
                }
            }
            default -> throw new IllegalStateException("未知知识库类型: " + kb.getType());
        }
    }

    private KnowledgeBaseView toView(KnowledgeBase kb, Viewer viewer) {
        return toView(kb, viewer, tagService.tagsByKnowledgeBaseIds(List.of(kb.getId())));
    }

    private KnowledgeBaseView toView(KnowledgeBase kb, Viewer viewer,
                                     Map<UUID, List<TagView>> tagsByKb) {
        requireUser(viewer.userId());
        boolean owner = kb.getOwnerUserId().equals(viewer.userId());
        boolean ownerOrAdmin = viewer.admin() || owner;
        KnowledgeBasePermission perm = resolvePermission(kb, viewer);
        boolean editable = perm == KnowledgeBasePermission.WRITE;
        String permissionStr = (perm == null) ? "NONE" : perm.name();
        OwnerView ownerView = resolveOwnerView(kb.getOwnerUserId());
        // external 连接配置仅对 owner/admin 可见，密钥永不回显
        ExternalProvider extProvider = ownerOrAdmin ? kb.getExternalProvider() : null;
        String extEndpoint = ownerOrAdmin ? kb.getExternalEndpoint() : null;
        return new KnowledgeBaseView(
                kb.getId(),
                kb.getName(),
                // description 列可空（V22 建表即无 NOT NULL，创建时前端留空会传
                // undefined 落成 NULL）。这里收敛成空串而不是让 null 出网：
                // 同一个 DTO 里 embeddingModelConfig / externalEndpoint 是有意
                // 可空的，description 没有这种「按权限隐藏」的语义，裸透传只会
                // 让每个消费方各自补一次 null 判断，前端就漏成了 500。
                isBlank(kb.getDescription()) ? "" : kb.getDescription(),
                kb.getType(),
                kb.getScope(),
                ownerView,
                redactEmbeddingConfig(kb.getEmbeddingModelConfig()),
                extProvider,
                extEndpoint,
                kb.getChunkSize(),
                kb.getChunkOverlap(),
                editable,
                permissionStr,
                tagsByKb.getOrDefault(kb.getId(), List.of()),
                kb.getCreatedAt(),
                kb.getUpdatedAt());
    }

    private GrantView toGrantView(KnowledgeBaseGrant g) {
        String principalName;
        if (g.getPrincipalType() == GrantPrincipalType.USER) {
            principalName = userRepository.findById(g.getPrincipalId())
                    .map(u -> (u.getName() != null && !u.getName().isBlank())
                            ? u.getName() : u.getUsername())
                    .orElseGet(() -> g.getPrincipalId().toString());
        } else {
            principalName = groupRepository.findById(g.getPrincipalId())
                    .map(Group::getName)
                    .orElseGet(() -> g.getPrincipalId().toString());
        }
        return new GrantView(
                g.getId(),
                g.getPrincipalType(),
                g.getPrincipalId(),
                principalName,
                g.getPermission(),
                g.getGrantedBy(),
                g.getCreatedAt());
    }

    private OwnerView resolveOwnerView(UUID ownerUserId) {
        return userRepository.findById(ownerUserId)
                .map(u -> new OwnerView(u.getId(), ownerName(u)))
                .orElseGet(() -> new OwnerView(ownerUserId, ownerUserId.toString()));
    }

    private String ownerName(User u) {
        if (u.getName() != null && !u.getName().isBlank()) {
            return u.getName();
        }
        return u.getUsername();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 将重叠收敛到 [0, size/2]，与 {@link KnowledgeChunker} 保持一致。 */
    private static int clampOverlap(int size, int overlap) {
        return Math.max(0, Math.min(overlap, size / 2));
    }
}
