package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.LibraryNode;
import com.tipdm.agentplatform.domain.LibraryNodeGrant;
import com.tipdm.agentplatform.domain.LibraryPermission;
import com.tipdm.agentplatform.domain.LibrarySpace;
import com.tipdm.agentplatform.domain.LibrarySpaceCategory;
import com.tipdm.agentplatform.domain.UserGroup;
import com.tipdm.agentplatform.repository.LibraryNodeGrantRepository;
import com.tipdm.agentplatform.repository.LibraryNodeRepository;
import com.tipdm.agentplatform.repository.LibrarySpaceRepository;
import com.tipdm.agentplatform.repository.UserGroupRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 资料库 ACL：空间与节点的准入判定，收敛到一处。
 *
 * <p>有效权限 = max(owner→WRITE, admin→WRITE, 节点直接授权, 群组展开授权)，
 * 与 {@code KnowledgeBaseService.resolvePermission} 同一套四层并集模型，
 * 但粒度更细：授权挂在<em>节点</em>上，且经祖先链冒泡——
 * 授权给一个节点即覆盖其整棵子树。</p>
 *
 * <p><b>授权表一表两档</b>：{@code library_node_grants} 里 {@code node_id IS NULL}
 * 是空间级（团队空间成员），非空是节点级。空间级成员在
 * {@link #resolveSpacePermission} 与 {@link #resolveNodePermission} 都要参与，
 * 漏一处就会出现「空间里看得到、点进去说节点不存在」。</p>
 *
 * <p>两类非对称判定（与知识库一致）：</p>
 * <ul>
 *   <li>空间/节点<b>不存在</b>或软删 → 404（不暴露存在性）；</li>
 *   <li>存在但<b>无权限</b> → 404 而非 403，同样不暴露"这里有个东西"。</li>
 * </ul>
 *
 * <p>非 HTTP 入口（智能体工具、中间件）用 {@link Viewer#runtime}：
 * 按非管理员处理，fail-close —— 运行期资源访问依赖显式授权与归属，
 * 不因"恰好是管理员"放大权限。</p>
 */
@Service
public class LibraryAccessService {

    /** 树深度上限；超过则拒绝创建，防止 path 无界增长。 */
    public static final int MAX_DEPTH = 10;

    private final LibrarySpaceRepository spaceRepository;
    private final LibraryNodeRepository nodeRepository;
    private final LibraryNodeGrantRepository grantRepository;
    private final UserGroupRepository userGroupRepository;

    public LibraryAccessService(LibrarySpaceRepository spaceRepository,
                                LibraryNodeRepository nodeRepository,
                                LibraryNodeGrantRepository grantRepository,
                                UserGroupRepository userGroupRepository) {
        this.spaceRepository = spaceRepository;
        this.nodeRepository = nodeRepository;
        this.grantRepository = grantRepository;
        this.userGroupRepository = userGroupRepository;
    }

    // ── 空间 ──────────────────────────────────────────────────────────

    /**
     * 取用户的个人空间，不存在则惰性创建。
     *
     * <p>惰性创建而非注册时建表：省一次注册流程耦合，且用户未必立刻用资料库。</p>
     */
    @Transactional
    public LibrarySpace personalSpace(UUID userId) {
        return spaceRepository.findByOwnerUserIdAndCategory(userId, LibrarySpaceCategory.personal)
                .orElseGet(() -> {
                    LibrarySpace space = new LibrarySpace();
                    // id 由 BaseEntity 在 persist 时生成；此处先赋值以便日志可追溯
                    space.setId(UUID.randomUUID());
                    space.setName("我的资料");
                    space.setCategory(LibrarySpaceCategory.personal);
                    space.setOwnerUserId(userId);
                    return spaceRepository.save(space);
                });
    }

    /** 至少 READ 权限的空间（owner / 管理端视角 / 默认权限）。 */
    public LibrarySpace requireVisibleSpace(UUID spaceId, Viewer viewer) {
        LibrarySpace space = spaceRepository.findById(spaceId)
                .orElseThrow(() -> ApiException.notFound("空间不存在"));
        if (resolveSpacePermission(space, viewer) == null) {
            throw ApiException.notFound("空间不存在");
        }
        return space;
    }

    /** 可写空间（owner / 管理端视角 / WRITE 默认权限）。 */
    public LibrarySpace requireWritableSpace(UUID spaceId, Viewer viewer) {
        LibrarySpace space = requireVisibleSpace(spaceId, viewer);
        if (resolveSpacePermission(space, viewer) != LibraryPermission.WRITE) {
            throw ApiException.forbidden("无权修改该空间");
        }
        return space;
    }

    /**
     * 空间<b>所有者</b>判据：可改空间设置、可管成员、可删空间。
     *
     * <p>与 {@link #requireWritableSpace} 刻意分开——「能写节点」不等于「能管空间」。
     * 团队空间给了 WRITE 成员后，他能上传建文件，但不该能改空间名、
     * 把别人踢出去、或把整个空间删掉（后者是不可逆操作）。</p>
     */
    public LibrarySpace requireOwnedSpace(UUID spaceId, Viewer viewer) {
        LibrarySpace space = spaceRepository.findById(spaceId)
                .orElseThrow(() -> ApiException.notFound("空间不存在"));
        if (!isSpaceOwner(space, viewer)) {
            throw ApiException.notFound("空间不存在");
        }
        return space;
    }

    /** 是否为空间所有者（管理端视角不算：删空间会连带清磁盘，必须由 owner 明确执行）。 */
    public boolean isSpaceOwner(LibrarySpace space, Viewer viewer) {
        return space.getOwnerUserId().equals(viewer.userId());
    }

    // ── 节点 ──────────────────────────────────────────────────────────

    /**
     * 至少 READ 权限的节点。**这是 {@code library://node/<id>} 引用链路的判权入口**，
     * 每次工具调用都必经此方法，故不做任何缓存（ACL 必须每次复核）。
     */
    public LibraryNode requireVisibleNode(UUID nodeId, Viewer viewer) {
        LibraryNode node = nodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> ApiException.notFound("节点不存在"));
        if (resolveNodePermission(node, viewer) == null) {
            throw ApiException.notFound("节点不存在");
        }
        return node;
    }

    /** 可写节点。 */
    public LibraryNode requireWritableNode(UUID nodeId, Viewer viewer) {
        LibraryNode node = requireVisibleNode(nodeId, viewer);
        if (resolveNodePermission(node, viewer) != LibraryPermission.WRITE) {
            throw ApiException.forbidden("无权修改该节点");
        }
        return node;
    }

    // ── 内部辅助 ──────────────────────────────────────────────────────

    /** 解析某用户对某空间的权限；无授权返回 {@code null}（NONE，不落库）。 */
    public LibraryPermission resolveSpacePermission(LibrarySpace space, Viewer viewer) {
        if (isSpaceOwner(space, viewer) || viewer.admin()) {
            return LibraryPermission.WRITE;
        }
        // 非 owner：先看空间级成员授权，再回退空间默认权限；都没有即不可见
        LibraryPermission granted = spaceLevelPermission(space.getId(), viewer.userId());
        if (granted != null) {
            return granted;
        }
        return space.getDefaultPermission();
    }

    /**
     * 解析某用户对某节点的权限；无授权返回 {@code null}。
     *
     * <p>顺序：owner / admin 先短路；再取空间级成员授权；
     * 最后取该用户在本空间收到的节点级授权，沿祖先链取最大——授权挂任一祖先即覆盖其子树。</p>
     */
    public LibraryPermission resolveNodePermission(LibraryNode node, Viewer viewer) {
        UUID userId = viewer.userId();
        if (node.getCreatedBy().equals(userId)) {
            return LibraryPermission.WRITE;
        }
        LibrarySpace space = spaceRepository.findById(node.getSpaceId()).orElse(null);
        if (space != null && space.getOwnerUserId().equals(userId)) {
            return LibraryPermission.WRITE;
        }
        if (viewer.admin()) {
            return LibraryPermission.WRITE;
        }

        // 空间级授权：既是空间可见性的来源，也是节点权限的下界。
        // ⚠️ 少了这一行，只读成员会「空间进得去、每个节点却都404」——
        // 因为节点级授权表里从来没有他这一行。
        LibraryPermission best = space == null
                ? null
                : max(spaceLevelPermission(node.getSpaceId(), userId),
                        space.getDefaultPermission());

        // 本空间内该用户收到的全部节点级授权，按节点归位后沿 path 取最大
        Map<UUID, LibraryPermission> granted = grantedPermissionsByNode(node.getSpaceId(), userId);
        for (String id : pathIds(node.getNodePath())) {
            LibraryPermission p = granted.get(UUID.fromString(id));
            if (p != null) {
                best = max(best, p);
                if (best == LibraryPermission.WRITE) {
                    return LibraryPermission.WRITE;
                }
            }
        }
        return best;
    }

    /**
     * 我在某空间的成员权限（只查空间级授权那一档），无则 {@code null}。
     *
     * <p>用户直授与所属群组展开取最大。群组 id 每次现查——
     * 群组成员会变，缓存下来等于让"退群后仍保留授权"变成事实。</p>
     */
    public LibraryPermission spaceLevelPermission(UUID spaceId, UUID userId) {
        if (spaceId == null) {
            return null;
        }
        List<LibraryNodeGrant> grants = grantRepository.findBySpaceIdAndNodeIdIsNull(spaceId);
        if (grants.isEmpty()) {
            return null;
        }
        Set<UUID> myGroups = userGroupRepository.findByUserId(userId).stream()
                .map(UserGroup::getGroupId)
                .collect(Collectors.toSet());

        LibraryPermission best = null;
        for (LibraryNodeGrant g : grants) {
            boolean hit = g.getPrincipalType() == GrantPrincipalType.USER
                    ? g.getPrincipalId().equals(userId)
                    : myGroups.contains(g.getPrincipalId());
            if (hit) {
                best = max(best, g.getPermission());
                if (best == LibraryPermission.WRITE) {
                    return LibraryPermission.WRITE;
                }
            }
        }
        return best;
    }

    /**
     * 我作为成员参与的空间 id 集合（owner 之外的那些）。
     *
     * <p>列可见空间时必须并上这一批：只查
     * {@code findByOwnerUserId} 的话，被授权的团队空间在侧栏根本不出现——
     * 授权做对了也没人能看见。</p>
     */
    public Set<UUID> memberSpaceIds(UUID userId) {
        Set<UUID> ids = new HashSet<>();
        Set<UUID> myGroups = userGroupRepository.findByUserId(userId).stream()
                .map(UserGroup::getGroupId)
                .collect(Collectors.toSet());
        if (!myGroups.isEmpty()) {
            ids.addAll(grantRepository.findSpaceIdsGrantedToGroups(myGroups));
        }
        // 用户直授：入参不能是空集合（JPQL 的 in () 恒假且部分实现直接报错），
        // 故与自己的 id 合并——自己在集合里时不会误命中，因为主键不同。
        Set<UUID> direct = new HashSet<>(myGroups);
        direct.add(userId);
        ids.addAll(grantRepository.findSpaceIdsGrantedToUser(direct));
        return ids;
    }

    /** 取用户在某空间收到的<b>节点级</b>授权，按节点 id 归位取最大。 */
    private Map<UUID, LibraryPermission> grantedPermissionsByNode(UUID spaceId, UUID userId) {
        if (spaceId == null) {
            return Map.of();
        }
        List<LibraryNodeGrant> grants = grantRepository.findBySpaceId(spaceId);
        if (grants.isEmpty()) {
            return Map.of();
        }
        Set<UUID> myGroups = userGroupRepository.findByUserId(userId).stream()
                .map(UserGroup::getGroupId)
                .collect(Collectors.toSet());

        Map<UUID, LibraryPermission> byNode = new HashMap<>();
        for (LibraryNodeGrant g : grants) {
            // 空间级授权不在此归位：它由 spaceLevelPermission 单独处理。
            // 不加这个判断会把 null 塞进 Map，此后 pathIds 里永远匹配不到，
            // 且 get(null) 恒为 null——静默失效，比抛异常更难查。
            if (g.getNodeId() == null) {
                continue;
            }
            boolean hit = g.getPrincipalType() == GrantPrincipalType.USER
                    ? g.getPrincipalId().equals(userId)
                    : myGroups.contains(g.getPrincipalId());
            if (hit) {
                byNode.merge(g.getNodeId(), g.getPermission(), LibraryAccessService::max);
            }
        }
        return byNode;
    }

    /** 从物化路径取出全部祖先 id（含自身）。 */
    private static List<String> pathIds(String nodePath) {
        String trimmed = nodePath.startsWith("/") ? nodePath.substring(1) : nodePath;
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? List.of() : List.of(trimmed.split("/"));
    }

    private static LibraryPermission max(LibraryPermission a, LibraryPermission b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return (a == LibraryPermission.WRITE || b == LibraryPermission.WRITE)
                ? LibraryPermission.WRITE
                : LibraryPermission.READ;
    }
}

