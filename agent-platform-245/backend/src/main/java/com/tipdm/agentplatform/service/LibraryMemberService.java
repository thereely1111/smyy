package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.Group;
import com.tipdm.agentplatform.domain.LibraryNodeGrant;
import com.tipdm.agentplatform.domain.LibraryPermission;
import com.tipdm.agentplatform.domain.LibrarySpace;
import com.tipdm.agentplatform.domain.LibrarySpaceCategory;
import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.repository.GroupRepository;
import com.tipdm.agentplatform.repository.LibraryNodeGrantRepository;
import com.tipdm.agentplatform.repository.UserRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.LibraryDtos.GrantMemberRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.ListMembersResponse;
import com.tipdm.agentplatform.web.dto.LibraryDtos.MemberView;
import com.tipdm.agentplatform.web.error.ApiException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 团队空间成员管理：把空间的读 / 写权限授予用户或群组。
 *
 * <p>授权落在 {@code library_node_grants} 的<b>空间级</b>一档（{@code node_id IS NULL}），
 * 与节点级授权共表——判权路径本来就只查这一张表，
 * 拆两张表会让每次判权多一次查询，且两份数据可能互相矛盾。</p>
 *
 * <p>准入只有一条：<b>空间所有者</b>。管理端视角（{@code viewer.admin}）也不算——
 * 管成员是「替团队决定谁能看什么」，与「删除整个空间」一样属于
 * 不该由旁观者代劳的动作。</p>
 */
@Service
public class LibraryMemberService {

    private final LibraryNodeGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final LibraryAccessService accessService;
    private final GroupService groupService;

    @PersistenceContext
    private EntityManager em;

    public LibraryMemberService(LibraryNodeGrantRepository grantRepository,
                                UserRepository userRepository,
                                GroupRepository groupRepository,
                                LibraryAccessService accessService,
                                GroupService groupService) {
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.accessService = accessService;
        this.groupService = groupService;
    }

    /**
     * 列空间成员 + 可授予群组。
     *
     * <p>所有者天然拥有 WRITE，不在成员表里——但前端要能显示他，
     * 故以 {@code grantId=null} 的一行返回。</p>
     */
    @Transactional(readOnly = true)
    public ListMembersResponse listMembers(UUID spaceId, Viewer viewer) {
        LibrarySpace space = accessService.requireOwnedSpace(spaceId, viewer);
        List<LibraryNodeGrant> grants = grantRepository.findBySpaceIdAndNodeIdIsNull(spaceId);

        // 主体名批量取一次：逐行 findById 会在成员多时退化成 N+1 查询
        Map<UUID, String> userNames = new HashMap<>();
        for (LibraryNodeGrant g : grants) {
            if (g.getPrincipalType() == GrantPrincipalType.USER) {
                userNames.computeIfAbsent(g.getPrincipalId(),
                        id -> userRepository.findById(id).map(User::getUsername).orElse(null));
            }
        }
        Map<UUID, String> groupNames = new HashMap<>();
        for (LibraryNodeGrant g : grants) {
            if (g.getPrincipalType() == GrantPrincipalType.GROUP) {
                groupNames.computeIfAbsent(g.getPrincipalId(),
                        id -> groupRepository.findById(id).map(Group::getName).orElse(null));
            }
        }

        List<MemberView> members = new ArrayList<>(grants.size() + 1);
        members.add(ownerRow(space));
        grants.stream()
                .map(g -> toView(g, nameOf(g, userNames, groupNames)))
                .sorted(Comparator.comparing(MemberView::displayName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(members::add);

        return new ListMembersResponse(members, groupService.summariesForGranting(viewer));
    }

    private static String nameOf(LibraryNodeGrant g,
            Map<UUID, String> userNames, Map<UUID, String> groupNames) {
        return g.getPrincipalType() == GrantPrincipalType.USER
                ? userNames.get(g.getPrincipalId())
                : groupNames.get(g.getPrincipalId());
    }

    /**
     * 授予 / 调整成员权限。
     *
     * <p><b>覆盖而非取最大</b>：降级（WRITE→READ）是owner 的正常操作，
     * 取最大值会让降级永远不生效。</p>
     */
    @Transactional
    public MemberView grant(UUID spaceId, GrantMemberRequest req, Viewer viewer) {
        LibrarySpace space = accessService.requireOwnedSpace(spaceId, viewer);
        if (space.getCategory() == LibrarySpaceCategory.personal) {
            throw ApiException.badRequest("个人空间无需授权成员");
        }
        String display = validatePrincipal(req);

        Optional<LibraryNodeGrant> existing = grantRepository
                .findBySpaceIdAndNodeIdIsNullAndPrincipalTypeAndPrincipalId(
                        spaceId, req.principalType(), req.principalId());
        LibraryNodeGrant grant;
        if (existing.isPresent()) {
            grant = existing.get();
            grant.setPermission(req.permission());
        } else {
            grant = new LibraryNodeGrant();
            grant.setId(UUID.randomUUID());
            grant.setNodeId(null); // 空间级
            grant.setSpaceId(spaceId);
            grant.setPrincipalType(req.principalType());
            grant.setPrincipalId(req.principalId());
            grant.setPermission(req.permission());
            grant.setGrantedBy(viewer.userId());
            em.persist(grant);
        }
        return toView(grant, display);
    }

    /** 撤销成员。owner 自己的那条不是授权行，不在此列（也就不可能被撤）。 */
    @Transactional
    public void revoke(UUID spaceId, UUID grantId, Viewer viewer) {
        accessService.requireOwnedSpace(spaceId, viewer);
        LibraryNodeGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> ApiException.notFound("授权不存在"));
        if (grant.getNodeId() != null || !grant.getSpaceId().equals(spaceId)) {
            // 节点级授权行误传 id，或授权属于别的空间：都按不存在处理
            throw ApiException.notFound("授权不存在");
        }
        grantRepository.delete(grant);
    }

    /**
     * 主体校验：必须真实存在，否则返回 400 而非静默写一条悬空授权。
     *
     * <p>悬空授权的危害在于<b>它看起来是生效的</b>：库里有一行、
     * 成员列表里也列得出来，但这个 id 永远匹配不到任何真人。</p>
     */
    private String validatePrincipal(GrantMemberRequest req) {
        if (req.principalType() == GrantPrincipalType.USER) {
            return userRepository.findById(req.principalId())
                    .map(User::getUsername)
                    .orElseThrow(() -> ApiException.badRequest("用户不存在"));
        }
        return groupRepository.findById(req.principalId())
                .map(Group::getName)
                .orElseThrow(() -> ApiException.badRequest("群组不存在"));
    }

    private MemberView ownerRow(LibrarySpace space) {
        return new MemberView(null, GrantPrincipalType.USER, space.getOwnerUserId(),
                userRepository.findById(space.getOwnerUserId())
                        .map(User::getUsername).orElse(null),
                LibraryPermission.WRITE, space.getOwnerUserId(), space.getCreatedAt());
    }

    private static MemberView toView(LibraryNodeGrant g, String displayName) {
        return new MemberView(g.getId(), g.getPrincipalType(), g.getPrincipalId(), displayName,
                g.getPermission(), g.getGrantedBy(), g.getCreatedAt());
    }
}
