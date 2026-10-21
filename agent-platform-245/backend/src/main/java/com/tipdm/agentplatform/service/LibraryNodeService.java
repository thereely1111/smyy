package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.LibraryNode;
import com.tipdm.agentplatform.domain.LibraryNodeKind;
import com.tipdm.agentplatform.domain.LibraryPermission;
import com.tipdm.agentplatform.repository.LibraryNodeRepository;
import com.tipdm.agentplatform.repository.LibrarySpaceRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.LibraryDtos.CreateNodeRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.ListNodesResponse;
import com.tipdm.agentplatform.web.dto.LibraryDtos.MoveNodeRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.NodeRefView;
import com.tipdm.agentplatform.web.dto.LibraryDtos.NodeView;
import com.tipdm.agentplatform.web.error.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 资料库节点树的增删改查。
 *
 * <p>树以 {@code parentId} + 物化 {@code nodePath} 双写：
 * 新建节点拼path；移动节点改父指针并<b>重写整棵子树的 path</b>（写贵换读快——
 * 本项目树浅、写频远低于读频）。深度上限 {@link LibraryAccessService#MAX_DEPTH}
 * 在 service 层拦截，不靠 DB 约束。</p>
 *
 * <p>⚠️ 路径格式以 '/' 分隔且<b>首尾都保留分隔符</b>（如 {@code /root/child/}），
 * 使前缀匹配天然只命中子树，无需再补LIKE 的边界条件。改动此约定必须同步
 * {@code LibraryNodeRepository.findSubtree}。</p>
 */
@Service
public class LibraryNodeService {

    private final LibraryNodeRepository nodeRepository;
    private final LibrarySpaceRepository spaceRepository;
    private final LibraryAccessService accessService;
    private final LibraryBlobStore blobStore;

    public LibraryNodeService(LibraryNodeRepository nodeRepository,
                              LibrarySpaceRepository spaceRepository,
                              LibraryAccessService accessService,
                              LibraryBlobStore blobStore) {
        this.nodeRepository = nodeRepository;
        this.spaceRepository = spaceRepository;
        this.accessService = accessService;
        this.blobStore = blobStore;
    }

    // ── 读 ────────────────────────────────────────────────────────────

    /**
     * 列某父节点的直接子节点；{@code parentId} 为空表示列空间根下节点。
     * 这是目录树一层的展开，是本模块最热的读路径。
     */
    @Transactional(readOnly = true)
    public ListNodesResponse listChildren(UUID spaceId, UUID parentId, Viewer viewer) {
        accessService.requireVisibleSpace(spaceId, viewer);
        if (parentId != null) {
            //父节点也要可见，否则可借"列子节点"探测不可见节点的存在性
            LibraryNode parent = nodeRepository.findByIdAndDeletedAtIsNull(parentId)
                    .orElseThrow(() -> ApiException.notFound("节点不存在"));
            if (accessService.resolveNodePermission(parent, viewer) == null) {
                throw ApiException.notFound("节点不存在");
            }
        }
        List<LibraryNode> children = nodeRepository
                .findBySpaceIdAndParentIdAndDeletedAtIsNullOrderBySortOrderAsc(spaceId, parentId);
        return new ListNodesResponse(children.stream()
                .map(n -> toView(n, viewer, false))
                .toList(), children.size());
    }

    /** 单节点详情。 */
    @Transactional(readOnly = true)
    public NodeView get(UUID nodeId, Viewer viewer) {
        LibraryNode node = accessService.requireVisibleNode(nodeId, viewer);
        boolean hasChildren = !nodeRepository
                .findBySpaceIdAndParentIdAndDeletedAtIsNullOrderBySortOrderAsc(
                        node.getSpaceId(), nodeId).isEmpty();
        return toView(node, viewer, hasChildren);
    }

    // ── 写 ────────────────────────────────────────────────────────────

    /**
     * 新建节点。
     *
     * <p>根节点 id 必须<b>先于</b> path 拼装生成——path 由自身 id 参与构成，
     * 所以不能依赖 BaseEntity 在 persist 时补id。</p>
     */
    @Transactional
    public NodeView create(UUID spaceId, CreateNodeRequest req, Viewer viewer) {
        accessService.requireWritableSpace(spaceId, viewer);

        LibraryNode parent = null;
        if (req.parentId() != null) {
            parent = accessService.requireVisibleNode(req.parentId(), viewer);
            if (!parent.getSpaceId().equals(spaceId)) {
                throw ApiException.badRequest("父节点不属于该空间");
            }
        }
        if (req.kind() == LibraryNodeKind.knowledge && req.payloadKbId() == null) {
            throw ApiException.badRequest("知识库节点必须指定 payloadKbId");
        }

        LibraryNode node = new LibraryNode();
        // 自行生成 id：node_path 需在 persist 前就拼好，不能等 BaseEntity 兜底
        node.setId(UUID.randomUUID());
        node.setSpaceId(spaceId);
        node.setParentId(req.parentId());
        node.setNodePath(buildPath(parent, node.getId()));
        node.setKind(req.kind());
        node.setTitle(req.title().trim());
        node.setPayloadKbId(req.payloadKbId());
        node.setCreatedBy(viewer.userId());
        node.setSortOrder(anchorOrder(spaceId, req.parentId(), null));

        LibraryNode saved = nodeRepository.save(node);
        return toView(saved, viewer, false);
    }

    /**
     * 移动节点（同空间内换父）。
     *
     * <p>三处校验缺一不可：目标父必须可见且同空间；<b>不能移进自己的子树</b>
     * （否则该子树 path 会被改写成自引用，节点从此不可达）；移动后深度不得超限。</p>
     *
     * <p>移动成功后<b>重写整棵子树的 path</b>，这也是"写贵"的具体体现。</p>
     */
    @Transactional
    public NodeView move(UUID nodeId, MoveNodeRequest req, Viewer viewer) {
        LibraryNode node = accessService.requireWritableNode(nodeId, viewer);

        LibraryNode targetParent = null;
        if (req.parentId() != null) {
            targetParent = accessService.requireWritableNode(req.parentId(), viewer);
            if (!targetParent.getSpaceId().equals(node.getSpaceId())) {
                throw ApiException.badRequest("不能跨空间移动");
            }
            if (targetParent.getId().equals(nodeId)) {
                throw ApiException.badRequest("不能把节点移动到自身之下");
            }
            // 目标父若在自身子树内，移动会使该子树 path 自引用 ⇒ 整棵子树不可达
            if (isInSubtree(targetParent, node)) {
                throw ApiException.badRequest("不能把节点移动到自己的子节点之下");
            }
        }

        List<LibraryNode> subtree = nodeRepository.findSubtree(node.getNodePath(), nodeId);
        int targetDepth = targetParent == null ? 0 : nodePathDepth(targetParent.getNodePath());
        // 移动后最深的叶子 = 目标父深度 + 子树内相对根的最大深度
        if (targetDepth + maxRelativeDepth(node.getNodePath(), subtree) > LibraryAccessService.MAX_DEPTH) {
            throw ApiException.badRequest("移动后树深度将超过 "
                    + LibraryAccessService.MAX_DEPTH + " 层");
        }

        node.setParentId(targetParent == null ? null : targetParent.getId());
        node.setNodePath(buildPath(targetParent, node.getId()));
        node.setSortOrder(anchorOrder(node.getSpaceId(), node.getParentId(), req.afterNodeId()));
        nodeRepository.save(node);

        // 重写子树 path：旧前缀整体替换为新前缀
        String oldPrefix = node.getNodePath();
        String newPrefix = buildPath(targetParent, node.getId());
        if (!oldPrefix.equals(newPrefix)) {
            for (LibraryNode child : subtree) {
                if (child.getNodePath().startsWith(oldPrefix)) {
                    child.setNodePath(newPrefix + child.getNodePath().substring(oldPrefix.length()));
                }
            }
            nodeRepository.saveAll(subtree);
        }
        return toView(node, viewer, !subtree.isEmpty());
    }

    /** 改名。 */
    @Transactional
    public NodeView rename(UUID nodeId, String title, Viewer viewer) {
        LibraryNode node = accessService.requireWritableNode(nodeId, viewer);
        node.setTitle(title.trim());
        return toView(nodeRepository.save(node), viewer, false);
    }

    /** 更新摘要。 */
    @Transactional
    public NodeView updateExcerpt(UUID nodeId, String excerpt, Viewer viewer) {
        LibraryNode node = accessService.requireWritableNode(nodeId, viewer);
        node.setExcerpt(excerpt);
        return toView(nodeRepository.save(node), viewer, false);
    }

    /**
     * 写 doc 正文（markdown）：落盘后回填 {@code contentRel}。
     * 节点必须是 doc 类型——正文按 kind 分派，类型错配会让读取端拿错解析方式。
     */
    @Transactional
    public NodeView writeDoc(UUID nodeId, String markdown, Viewer viewer) {
        LibraryNode node = accessService.requireWritableNode(nodeId, viewer);
        if (node.getKind() != LibraryNodeKind.doc) {
            throw ApiException.badRequest("该节点不是文档类型");
        }
        String rel = blobStore.writeDoc(node.getSpaceId(), node.getId(), markdown);
        node.setContentRel(rel);
        // 摘要取正文首段，供列表页展示而不必读正文文件
        node.setExcerpt(summarize(markdown));
        LibraryNode saved = nodeRepository.save(node);
        return toView(saved, viewer, false);
    }

    /**
     * 建drive 节点并落盘文件。
     *
     * <p>先建节点拿到 id、再落盘、最后回填 {@code contentRel}——
     * 反序会在落盘失败时留下无正文的孤儿节点。</p>
     */
    @Transactional
    public DriveUploadResult uploadDriveFile(UUID spaceId, UUID parentId,
                                             String fileName, InputStream content,
                                             long declaredSize, Viewer viewer) {
        accessService.requireWritableSpace(spaceId, viewer);
        if (parentId != null) {
            LibraryNode parent = accessService.requireWritableNode(parentId, viewer);
            if (!parent.getSpaceId().equals(spaceId)) {
                throw ApiException.badRequest("父节点不属于该空间");
            }
        }
        String cleanName = baseName(fileName);

        LibraryNode node = new LibraryNode();
        node.setId(UUID.randomUUID());
        node.setSpaceId(spaceId);
        node.setParentId(parentId);
        LibraryNode parent = parentId == null ? null
                : nodeRepository.findByIdAndDeletedAtIsNull(parentId).orElse(null);
        node.setNodePath(buildPath(parent, node.getId()));
        node.setKind(LibraryNodeKind.drive);
        node.setTitle(cleanName);
        node.setCreatedBy(viewer.userId());
        node.setSortOrder(nodeRepository.nextSortOrder(spaceId, parentId));
        // 先落盘拿到 rel，再一次性 save：避免半成品节点
        String rel = blobStore.writeDriveFile(spaceId, cleanName, content);
        node.setContentRel(rel);
        LibraryNode saved = nodeRepository.save(node);

        return new DriveUploadResult(saved.getId(), saved.getTitle(), cleanName,
                declaredSize, NodeRefView.refOf(saved.getId()));
    }

    /** drive 上传结果。 */
    public record DriveUploadResult(UUID nodeId, String title, String fileName,
                                    long fileSize, String ref) {
    }

    /** 取摘要：首个非空段的前 200 字。 */
    private static String summarize(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        for (String line : markdown.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t.length() > 200 ? t.substring(0, 200) : t;
            }
        }
        return null;
    }

    /**
     * 软删节点：整棵子树一并标记删除，但<b>不清空 path、不删行</b>——
     * 保留行以免破坏其他节点的 path 前缀引用完整性。
     *
     * <p>正文文件<b>不随之删除</b>：软删可恢复，删文件则不可逆；
     * 空间删除时才整体清理（{@link LibraryBlobStore#deleteSpace}）。</p>
     */
    @Transactional
    public void delete(UUID nodeId, Viewer viewer) {
        LibraryNode node = accessService.requireWritableNode(nodeId, viewer);
        Instant now = Instant.now();
        node.setDeletedAt(now);
        nodeRepository.save(node);
        // 子树一并软删；path 与行都保留，避免破坏其他节点的 path 前缀引用
        List<LibraryNode> subtree = nodeRepository.findSubtree(node.getNodePath(), nodeId);
        subtree.forEach(n -> n.setDeletedAt(now));
        nodeRepository.saveAll(subtree);
        // ⚠️ 正文文件刻意不删：软删可恢复，删文件不可逆（见类注释）。
    }

    // ── 内部辅助 ──────────────────────────────────────────────────────

    /**
     * 取文件名本体：剥掉全部路径成分与包夹扩展名的「伪路径」。
     *
     * <p><b>为什么必须在后端收口</b>：目录上传时客户端可能把相对路径当文件名发来
     * （{@code webkitRelativePath} 的形态），落库后表现为「目录/文件.ext」——
     * 目录树已经表达了层级关系，标题再带一遍就是重复显示。
     * <b>前端规整只是体验层，这里才是唯一权威口径</b>：任何客户端
     * （含非浏览器 Agent、脚本直调）都从这里拿干净的标题。</p>
     *
     * <p>分隔符覆盖半角 {@code /}、反斜杠 {@code \}与全角 {@code ／}
     * ——资料包解压出的目录名常混用全角，取最后一个即可剥净。</p>
     */
    private static String baseName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "file";
        }
        String n = rawName.trim();
        int cut = -1;
        for (int i = n.length() - 1; i >= 0; i--) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == '／') {
                cut = i;
                break;
            }
        }
        if (cut >= 0) {
            n = n.substring(cut + 1);
        }
        n = n.trim();
        return n.isEmpty() ? "file" : n;
    }

    /** 拼物化路径：父路径 + 自身 id + 尾分隔符；根节点为 {@code /<id>/}。 */
    private static String buildPath(LibraryNode parent, UUID selfId) {
        if (parent == null) {
            return "/" + selfId + "/";
        }
        return parent.getNodePath() + selfId + "/";
    }

    /** 候选父节点是否落在 node 的子树内。 */
    private static boolean isInSubtree(LibraryNode candidate, LibraryNode node) {
        return candidate.getNodePath().startsWith(node.getNodePath())
                && !candidate.getId().equals(node.getId());
    }

    /** path 的层级数：{@code /a/b/} → 2。 */
    private static int nodePathDepth(String nodePath) {
        String t = nodePath.startsWith("/") ? nodePath.substring(1) : nodePath;
        if (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.isEmpty() ? 0 : t.split("/").length;
    }

    /**
     * 子树内各节点相对 {@code rootPath} 的最大层级偏移。
     * 用绝对深度差算，相对根自身的 0；直接用 depthOfSubtree 会把根的绝对深度算进去，导致误判超限。
     */
    private static int maxRelativeDepth(String rootPath, List<LibraryNode> subtree) {
        int rootDepth = nodePathDepth(rootPath);
        int max = 0;
        for (LibraryNode n : subtree) {
            max = Math.max(max, nodePathDepth(n.getNodePath()) - rootDepth);
        }
        return max;
    }

    /**
     * 计算新节点的排序位：给定同级锚点时插在其后，否则取末尾（max+1）。
     */
    private int anchorOrder(UUID spaceId, UUID parentId, UUID afterNodeId) {
        int next = nodeRepository.nextSortOrder(spaceId, parentId);
        if (afterNodeId == null) {
            return next;
        }
        LibraryNode anchor = nodeRepository.findByIdAndDeletedAtIsNull(afterNodeId)
                .orElseThrow(() -> ApiException.notFound("锚点节点不存在"));
        // 锚点之后的同级节点整体后移一位，让出插入位置
        List<LibraryNode> siblings = nodeRepository
                .findBySpaceIdAndParentIdAndDeletedAtIsNullOrderBySortOrderAsc(spaceId, parentId);
        int anchorIndex = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(afterNodeId)) {
                anchorIndex = i;
                break;
            }
        }
        if (anchorIndex < 0) {
            return next;
        }
        int order = anchor.getSortOrder();
        for (LibraryNode s : siblings.subList(anchorIndex + 1, siblings.size())) {
            s.setSortOrder(s.getSortOrder() + 1);
        }
        nodeRepository.saveAll(siblings);
        return order + 1;
    }

    private NodeView toView(LibraryNode n, Viewer viewer, boolean hasChildren) {
        LibraryPermission perm = accessService.resolveNodePermission(n, viewer);
        return new NodeView(
                n.getId(),
                n.getSpaceId(),
                n.getParentId(),
                n.getKind(),
                n.getTitle(),
                n.getSortOrder(),
                n.getPayloadKbId(),
                n.getExcerpt(),
                hasChildren,
                perm,
                perm == LibraryPermission.WRITE,
                n.getUpdatedAt());
    }

    /**
     * 取节点的对话引用串。
     *
     * <p>这是 {@code library://node/<id>} 引用链路的入口：前端拿它作为消息文本的一部分发送，
     * 模型看到后调用 {@code library_read(nodeId)} 回读正文。判权在此处即完成——
     * 拿不到引用串就说明无权访问该节点。</p>
     */
    @Transactional(readOnly = true)
    public NodeRefView refOf(UUID nodeId, Viewer viewer) {
        LibraryNode node = accessService.requireVisibleNode(nodeId, viewer);
        return new NodeRefView(node.getId(), NodeRefView.refOf(node.getId()),
                node.getTitle(), node.getKind());
    }

    /** 空间节点总数（空间卡片展示用）。 */
    @Transactional(readOnly = true)
    public long countNodes(UUID spaceId) {
        return nodeRepository.findBySpaceIdAndDeletedAtIsNull(spaceId).size();
    }

    /** 某节点是否已有子节点（列表标记"可展开"用）。 */
    @Transactional(readOnly = true)
    public boolean hasChildren(UUID nodeId) {
        return !nodeRepository
                .findBySpaceIdAndParentIdAndDeletedAtIsNullOrderBySortOrderAsc(
                        nodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                                .orElseThrow(() -> ApiException.notFound("节点不存在")).getSpaceId(), nodeId)
                .isEmpty();
    }
}


