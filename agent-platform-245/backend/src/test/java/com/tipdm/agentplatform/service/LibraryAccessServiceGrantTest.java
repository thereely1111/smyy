package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.LibraryNode;
import com.tipdm.agentplatform.domain.LibraryNodeGrant;
import com.tipdm.agentplatform.domain.LibraryNodeKind;
import com.tipdm.agentplatform.domain.LibraryPermission;
import com.tipdm.agentplatform.domain.LibrarySpace;
import com.tipdm.agentplatform.domain.LibrarySpaceCategory;
import com.tipdm.agentplatform.domain.UserGroup;
import com.tipdm.agentplatform.repository.LibraryNodeGrantRepository;
import com.tipdm.agentplatform.repository.LibraryNodeRepository;
import com.tipdm.agentplatform.repository.LibrarySpaceRepository;
import com.tipdm.agentplatform.repository.UserGroupRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 团队空间的授权判权。
 *
 * <p><b>这些不变量为什么必须有测试</b>：判权链有三条并行的放行条件
 * （owner /空间级授权 / 节点级授权），而失败形态是<b>静默的</b>——
 * 少算一条不会抛异常，只会让人「看不见本该看见的东西」或
 * 「能改本该改不了的东西」。本仓零集成基座（无起真实容器的测试），
 * 判权一旦写错只能等用户在页面上发现。</p>
 *
 * <p>三条最容易被写错的地方，各有对应用例：</p>
 * <ol>
 *   <li>空间级授权只进空间判权、不进节点判权 → 只读成员「进得去、点进去全404」；</li>
 *   <li>空间级授权行（{@code nodeId == null}）混进节点级归位 → null 塞进 Map，
 *       后续匹配永远落空；</li>
 *   <li>owner 判定混入 admin → 管理端能删别人的团队空间。</li>
 * </ol>
 */
class LibraryAccessServiceGrantTest {

    private static final UUID SPACE_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final UUID STRANGER_ID = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();

    private LibraryAccessService service;
    private LibrarySpaceRepository spaceRepository;
    private LibraryNodeRepository nodeRepository;
    private LibraryNodeGrantRepository grantRepository;
    private UserGroupRepository userGroupRepository;

    @BeforeEach
    void setUp() {
        spaceRepository = mock(LibrarySpaceRepository.class);
        nodeRepository = mock(LibraryNodeRepository.class);
        grantRepository = mock(LibraryNodeGrantRepository.class);
        userGroupRepository = mock(UserGroupRepository.class);

        // 默认：无群组、无授权、空间存在
        when(userGroupRepository.findByUserId(any())).thenReturn(List.of());
        when(grantRepository.findBySpaceIdAndNodeIdIsNull(any())).thenReturn(List.of());
        when(grantRepository.findBySpaceId(any())).thenReturn(List.of());
        when(spaceRepository.findById(SPACE_ID))
                .thenReturn(Optional.of(teamSpace(null)));

        service = new LibraryAccessService(
                spaceRepository, nodeRepository, grantRepository, userGroupRepository);
    }

    private static LibrarySpace teamSpace(LibraryPermission defaultPermission) {
        LibrarySpace s = new LibrarySpace();
        s.setId(SPACE_ID);
        s.setName("市场部共享");
        s.setCategory(LibrarySpaceCategory.team);
        s.setOwnerUserId(OWNER_ID);
        s.setDefaultPermission(defaultPermission);
        return s;
    }

    /** 空间级授权行：{@code nodeId} 必须为 null，这是「整空间授权」的判据。 */
    private static LibraryNodeGrant spaceGrant(GrantPrincipalType type, UUID principalId,
            LibraryPermission permission) {
        LibraryNodeGrant g = new LibraryNodeGrant();
        g.setId(UUID.randomUUID());
        g.setNodeId(null);
        g.setSpaceId(SPACE_ID);
        g.setPrincipalType(type);
        g.setPrincipalId(principalId);
        g.setPermission(permission);
        return g;
    }

    private static LibraryNode nodeInSpace(UUID nodeId, UUID createdBy, String path) {
        LibraryNode n = new LibraryNode();
        n.setId(nodeId);
        n.setSpaceId(SPACE_ID);
        n.setKind(LibraryNodeKind.doc);
        n.setTitle("资料");
        n.setCreatedBy(createdBy);
        n.setNodePath(path);
        return n;
    }

    // ── 空间判权 ──────────────────────────────────────────────────

    @Test
    void ownerAlwaysGetsWrite() {
        assertEquals(LibraryPermission.WRITE,
                service.resolveSpacePermission(teamSpace(null), new Viewer(OWNER_ID, false)));
    }

    @Test
    void strangerCannotSeeSpaceWhenNoGrantAndNoDefault() {
        // 团队空间默认权限为 null 且无授权 ⇒ 非成员不可见（返回 null 而非 READ）
        assertNull(service.resolveSpacePermission(teamSpace(null), new Viewer(STRANGER_ID, false)));
    }

    @Test
    void readMemberGetsReadNotWrite() {
        // 这是「普通用户能查看使用、无管理功能」的核心断言
        when(grantRepository.findBySpaceIdAndNodeIdIsNull(SPACE_ID))
                .thenReturn(List.of(spaceGrant(GrantPrincipalType.USER, MEMBER_ID,
                        LibraryPermission.READ)));

        assertEquals(LibraryPermission.READ,
                service.resolveSpacePermission(teamSpace(null), new Viewer(MEMBER_ID, false)));
    }

    @Test
    void groupGrantAppliesToEveryMemberOfThatGroup() {
        UserGroup ug = new UserGroup();
        ug.setUserId(MEMBER_ID);
        ug.setGroupId(GROUP_ID);
        when(userGroupRepository.findByUserId(MEMBER_ID)).thenReturn(List.of(ug));
        when(grantRepository.findBySpaceIdAndNodeIdIsNull(SPACE_ID))
                .thenReturn(List.of(spaceGrant(GrantPrincipalType.GROUP, GROUP_ID,
                        LibraryPermission.WRITE)));

        assertEquals(LibraryPermission.WRITE,
                service.resolveSpacePermission(teamSpace(null), new Viewer(MEMBER_ID, false)));
    }

    @Test
    void defaultPermissionAppliesToEveryoneElse() {
        // 兜底：没被单独授权的人按空间默认权限放行
        assertEquals(LibraryPermission.READ,
                service.resolveSpacePermission(teamSpace(LibraryPermission.READ),
                        new Viewer(STRANGER_ID, false)));
    }

    // ── 节点判权：空间级授权必须参与 ────────────────────────────────

    @Test
    void spaceLevelReadGrantBecomesNodePermissionLowerBound() {
        // ⚠️ 本仓最容易漏的一条：只算节点级授权时，只读成员会
        // 「空间进得去、每个节点都 404」——节点级表里本来就没有他这一行。
        when(grantRepository.findBySpaceIdAndNodeIdIsNull(SPACE_ID))
                .thenReturn(List.of(spaceGrant(GrantPrincipalType.USER, MEMBER_ID,
                        LibraryPermission.READ)));
        LibraryNode node = nodeInSpace(UUID.randomUUID(), OWNER_ID, "/" + UUID.randomUUID() + "/");

        assertEquals(LibraryPermission.READ,
                service.resolveNodePermission(node, new Viewer(MEMBER_ID, false)),
                "空间级授权必须成为节点权限的下界，否则只读成员点进去全是「节点不存在」");
    }

    @Test
    void spaceLevelGrantDoesNotGrantWriteToReadOnlyMember() {
        // 反向断言：READ 成员不得因空间级授权而获得 WRITE，
        // 否则前端只按 editable 收权而后端放行，等于没收
        when(grantRepository.findBySpaceIdAndNodeIdIsNull(SPACE_ID))
                .thenReturn(List.of(spaceGrant(GrantPrincipalType.USER, MEMBER_ID,
                        LibraryPermission.READ)));
        LibraryNode node = nodeInSpace(UUID.randomUUID(), OWNER_ID, "/" + UUID.randomUUID() + "/");

        LibraryPermission perm = service.resolveNodePermission(node, new Viewer(MEMBER_ID, false));
        assertEquals(LibraryPermission.READ, perm);
        assertFalse(perm == LibraryPermission.WRITE, "只读成员绝不能被判为可写");
    }

    @Test
    void strangerSeesNoNodeInPrivateTeamSpace() {
        LibraryNode node = nodeInSpace(UUID.randomUUID(), OWNER_ID, "/" + UUID.randomUUID() + "/");

        assertNull(service.resolveNodePermission(node, new Viewer(STRANGER_ID, false)),
                "非成员对私有团队空间的节点必须完全不可见");
    }

    @Test
    void nodeCreatedByUserGivesThatUserWrite() {
        UUID creator = UUID.randomUUID();
        LibraryNode node = nodeInSpace(UUID.randomUUID(), creator, "/" + UUID.randomUUID() + "/");

        assertEquals(LibraryPermission.WRITE,
                service.resolveNodePermission(node, new Viewer(creator, false)),
                "自己建的节点自己必然可写");
    }

    @Test
    void nodeLevelGrantOnAncestorBubblesDownToDescendant() {
        // 授权给某个节点即覆盖其整棵子树（沿物化路径冒泡）
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        LibraryNodeGrant nodeGrant = new LibraryNodeGrant();
        nodeGrant.setId(UUID.randomUUID());
        nodeGrant.setNodeId(rootId);
        nodeGrant.setSpaceId(SPACE_ID);
        nodeGrant.setPrincipalType(GrantPrincipalType.USER);
        nodeGrant.setPrincipalId(MEMBER_ID);
        nodeGrant.setPermission(LibraryPermission.WRITE);

        when(grantRepository.findBySpaceId(SPACE_ID)).thenReturn(List.of(nodeGrant));
        LibraryNode child = nodeInSpace(childId, OWNER_ID, "/" + rootId + "/" + childId + "/");

        assertEquals(LibraryPermission.WRITE,
                service.resolveNodePermission(child, new Viewer(MEMBER_ID, false)),
                "祖先节点的授权必须向下冒泡到子孙");
    }

    @Test
    void spaceLevelGrantRowDoesNotBreakNodeLevelLookup() {
        // 空间级授权（nodeId=null）混进节点级归位时，会把 null 塞进 HashMap，
        // 之后按路径取 id 永远匹配不到——节点级授权被静默吞掉。
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        LibraryNodeGrant nodeGrant = new LibraryNodeGrant();
        nodeGrant.setId(UUID.randomUUID());
        nodeGrant.setNodeId(rootId);
        nodeGrant.setSpaceId(SPACE_ID);
        nodeGrant.setPrincipalType(GrantPrincipalType.USER);
        nodeGrant.setPrincipalId(MEMBER_ID);
        nodeGrant.setPermission(LibraryPermission.WRITE);

        when(grantRepository.findBySpaceId(SPACE_ID)).thenReturn(List.of(
                spaceGrant(GrantPrincipalType.USER, STRANGER_ID, LibraryPermission.READ),
                nodeGrant));
        LibraryNode child = nodeInSpace(childId, OWNER_ID, "/" + rootId + "/" + childId + "/");

        assertEquals(LibraryPermission.WRITE,
                service.resolveNodePermission(child, new Viewer(MEMBER_ID, false)),
                "同表存在空间级行时，节点级授权仍须正确命中");
    }

    // ── owner 判定：管理端不算 owner ────────────────────────────────

    @Test
    void adminIsNotOwnerOfTeamSpace() {
        // 删除空间会连带清磁盘，是不可逆操作：
        // 旁观的管理端不该有「替别人删团队空间」的权限。
        LibrarySpace space = teamSpace(LibraryPermission.WRITE);
        assertFalse(service.isSpaceOwner(space, new Viewer(STRANGER_ID, true)),
                "管理端视角不等于空间所有者——删空间的判据刻意不含 admin");
    }

    @Test
    void ownerIsRecognisedRegardlessOfAdminFlag() {
        assertTrue(service.isSpaceOwner(teamSpace(null), new Viewer(OWNER_ID, false)));
    }

    // ── 可见空间：成员必须能在列表里看到 ────────────────────────────

    @Test
    void memberSpaceIdsIncludesSpacesGrantedDirectly() {
        when(grantRepository.findSpaceIdsGrantedToUser(any()))
                .thenReturn(List.of(SPACE_ID));

        Set<UUID> ids = service.memberSpaceIds(MEMBER_ID);

        assertTrue(ids.contains(SPACE_ID),
                "只查 owner 的空间列表会让被授权的团队空间在侧栏消失");
    }

    @Test
    void memberSpaceIdsIncludesSpacesGrantedViaGroup() {
        UserGroup ug = new UserGroup();
        ug.setUserId(MEMBER_ID);
        ug.setGroupId(GROUP_ID);
        when(userGroupRepository.findByUserId(MEMBER_ID)).thenReturn(List.of(ug));
        when(grantRepository.findSpaceIdsGrantedToGroups(any())).thenReturn(List.of(SPACE_ID));

        assertTrue(service.memberSpaceIds(MEMBER_ID).contains(SPACE_ID),
                "群组授权的空间同样要出现在可见列表里");
    }
}