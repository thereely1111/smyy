package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.LibraryPermission;
import com.tipdm.agentplatform.domain.LibrarySpace;
import com.tipdm.agentplatform.domain.LibrarySpaceCategory;
import com.tipdm.agentplatform.repository.LibrarySpaceRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.LibraryDtos.CreateSpaceRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.ListSpacesResponse;
import com.tipdm.agentplatform.web.dto.LibraryDtos.SpaceView;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 资料库空间管理。
 *
 * <p>可见性口径：本人创建的空间全部可见；他人团队空间仅在被授权（且授权挂在
 * 其空间内某个节点上、空间 id 冗余在授权行）时可见。</p>
 */
@Service
public class LibrarySpaceService {

    private static final Logger log = LoggerFactory.getLogger(LibrarySpaceService.class);

    private final LibrarySpaceRepository spaceRepository;
    private final LibraryAccessService accessService;
    private final LibraryNodeService nodeService;
    private final LibraryBlobStore blobStore;

    public LibrarySpaceService(LibrarySpaceRepository spaceRepository,
                               LibraryAccessService accessService,
                               LibraryNodeService nodeService,
                               LibraryBlobStore blobStore) {
        this.spaceRepository = spaceRepository;
        this.accessService = accessService;
        this.nodeService = nodeService;
        this.blobStore = blobStore;
    }

/**
 * 列当前用户可见的空间，个人空间排最前。
     *
     * <p><b>可见 = 我 owner 的 + 我作为成员参与的</b>。后者由
     * {@link LibraryAccessService#memberSpaceIds} 给出——只查
     * {@code findByOwnerUserId} 的话，别人授权给我的团队空间在侧栏根本不出现，
     * 授权功能做对了也没人能看见。</p>
     *
     * <p>逐个空间过一遍 {@code resolveSpacePermission} 作为最终闸门：
     * 空间级授权被撤销、成员已退群、或只剩 {@code defaultPermission} 为空的残档时，
     * 都在这一步被滤掉，不靠上游列表的准确性兜底。</p>
     *
     * <p><b>必须先确保个人空间存在</b>（{@code personalSpace} 惰性创建）：
     * 这是资料库首屏的唯一入口，若这里不建，新用户永远看到「还没有资料库空间」空态、
     * 且页面无任何入口能触发创建——前端虽有 {@code GET /spaces/personal}，但
     * 它不会被首屏调用（首屏要的是列表，不是单个）。
     * 契约写在 {@code LibraryDtos.ListSpacesResponse} 上：首屏无需判断个人空间存不存在。</p>
     *
     * <p>因此本方法<b>不能是 {@code readOnly}</b>：它会写库。
     * 代价是列表接口不再是纯读——但幂等（按 owner+category 判存在），
     * 重复调用不会产生第二个个人空间。</p>
     */
@Transactional
public ListSpacesResponse list(Viewer viewer) {
    // 惰性创建个人空间（幂等）；失败不阻断列表——已有团队空间仍应可见
    try {
        accessService.personalSpace(viewer.userId());
    } catch (RuntimeException e) {
        log.warn("创建个人空间失败，本次列表不含个人空间: {}", e.getMessage());
    }

    // owner 的空间 + 我作为成员参与的空间，去重后逐个判权
    Set<UUID> candidateIds = new LinkedHashSet<>();
    spaceRepository.findByOwnerUserId(viewer.userId())
            .forEach(s -> candidateIds.add(s.getId()));
    Set<UUID> memberOf = accessService.memberSpaceIds(viewer.userId());
    if (!memberOf.isEmpty()) {
        spaceRepository.findAllById(memberOf)
                .forEach(s -> candidateIds.add(s.getId()));
    }

    List<SpaceView> views = new ArrayList<>(candidateIds.size());
    for (LibrarySpace s : spaceRepository.findAllById(candidateIds)) {
        if (accessService.resolveSpacePermission(s, viewer) == null) {
            continue; // 授权已撤销 / 默认权限为空：不可见
        }
        views.add(toView(s, viewer));
    }
    views.sort(Comparator
            .comparing((SpaceView v) -> v.category().name().equals("personal") ? 0 : 1)
            .thenComparing(SpaceView::updatedAt, Comparator.reverseOrder()));
    return new ListSpacesResponse(views);
}

    /** 取个人空间，不存在则惰性创建。 */
    @Transactional
    public SpaceView personal(Viewer viewer) {
        return toView(accessService.personalSpace(viewer.userId()), viewer);
    }

    /** 单个空间。 */
    @Transactional(readOnly = true)
    public SpaceView get(UUID spaceId, Viewer viewer) {
        return toView(accessService.requireVisibleSpace(spaceId, viewer), viewer);
    }

    /** 建团队空间（个人空间走 {@link #personal} 惰性创建，不走此入口）。 */
    @Transactional
    public SpaceView create(CreateSpaceRequest req, Viewer viewer) {
        LibrarySpace space = new LibrarySpace();
        space.setId(UUID.randomUUID());
        space.setName(req.name().trim());
        space.setCategory(req.category());
        space.setOwnerUserId(viewer.userId());
        space.setDescription(req.description());
        // 个人空间保持"仅自己可见"：给它设默认权限等于绕过节点授权直接开权限
        space.setDefaultPermission(req.category() == LibrarySpaceCategory.team
                ? req.defaultPermission() : null);
        return toView(spaceRepository.save(space), viewer);
    }

    /** 改空间信息。仅所有者可改——WRITE 成员能写节点，不等于能改空间设置。 */
    @Transactional
    public SpaceView update(UUID spaceId, CreateSpaceRequest req, Viewer viewer) {
        LibrarySpace space = accessService.requireOwnedSpace(spaceId, viewer);
        space.setName(req.name().trim());
        space.setDescription(req.description());
        if (req.category() == LibrarySpaceCategory.team) {
            space.setDefaultPermission(req.defaultPermission());
        }
        return toView(spaceRepository.save(space), viewer);
    }

    /**
     * 删空间：智能体配置硬删 + 磁盘目录整体清理。
     *
     * <p><b>为什么必须同时删磁盘</b>：节点删除是软删（只置 {@code deleted_at}），
     * 正文文件留在盘上。若空间删除只删 智能体配置，{@code library_node.content_rel} 的
     * 唯一索引就断了，正文文件成为<b>永不回收的孤儿</b>——而软删可恢复、文件不可逆，
     * 所以清理只发生在空间这一层。</p>
     *
     * <p>⚠️ 个人空间不允许删：它是「惰性创建」出来的固定入口，
     * 删掉后 {@code personalSpace()} 会重新建一个空空间，用户体感是"资料全丢了"。</p>
     */
    @Transactional
    public void delete(UUID spaceId, Viewer viewer) {
        LibrarySpace space = accessService.requireOwnedSpace(spaceId, viewer);
        if (space.getCategory() == LibrarySpaceCategory.personal) {
            throw ApiException.badRequest("个人空间不可删除");
        }
        // 先删磁盘再删 DB：反序若磁盘清理失败会留下无主目录，但至少 DB 里已无引用可追
        blobStore.deleteSpace(spaceId);
        spaceRepository.delete(space);
    }

    /**
     * 空间详情页的权限信息，供前端决定编辑按钮显隐。
     * 单独暴露而非把permission 塞进所有视图，是为了不泄露无权空间的任何信息。
     */
    @Transactional(readOnly = true)
    public LibraryPermission resolvePermission(UUID spaceId, Viewer viewer) {
        return accessService.resolveSpacePermission(
                accessService.requireVisibleSpace(spaceId, viewer), viewer);
    }

    /** 是否为空间所有者：前端据此决定「成员管理 / 删除空间」入口显隐。 */
    @Transactional(readOnly = true)
    public boolean isOwner(UUID spaceId, Viewer viewer) {
        return accessService.isSpaceOwner(accessService.requireVisibleSpace(spaceId, viewer), viewer);
    }

    private SpaceView toView(LibrarySpace s, Viewer viewer) {
        LibraryPermission perm = accessService.resolveSpacePermission(s, viewer);
        return new SpaceView(
                s.getId(),
                s.getName(),
                s.getCategory(),
                s.getOwnerUserId(),
                s.getDefaultPermission(),
                s.getDescription(),
                perm,
                perm == LibraryPermission.WRITE,
                accessService.isSpaceOwner(s, viewer),
                nodeService.countNodes(s.getId()),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}

