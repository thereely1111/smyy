package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.Group;
import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.domain.UserGroup;
import com.tipdm.agentplatform.repository.GroupRepository;
import com.tipdm.agentplatform.repository.KnowledgeBaseGrantRepository;
import com.tipdm.agentplatform.repository.UserGroupRepository;
import com.tipdm.agentplatform.repository.UserRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.GroupDtos.AddMemberRequest;
import com.tipdm.agentplatform.web.dto.GroupDtos.CreateGroupRequest;
import com.tipdm.agentplatform.web.dto.GroupDtos.GroupMemberView;
import com.tipdm.agentplatform.web.dto.GroupDtos.GroupSummary;
import com.tipdm.agentplatform.web.dto.GroupDtos.GroupView;
import com.tipdm.agentplatform.web.dto.GroupDtos.ListGroupsResponse;
import com.tipdm.agentplatform.web.error.ApiException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 内置轻量群组管理：创建、成员增减、删除，以及为知识库授权提供的「可授予群组」清单。
 * 群组是知识库按群组批量授权的载体；群组删除时会清理指向它的知识库授权。
 *
 * <p>管理端视角（viewer.admin）由接口层按 admin:access 权限码裁决后传入，
 * 本类只做「创建者 or 管理端视角」的资源级判定，不感知用户角色。</p>
 */
@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final UserGroupRepository userGroupRepository;
    private final KnowledgeBaseGrantRepository grantRepository;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    public GroupService(
            GroupRepository groupRepository,
            UserGroupRepository userGroupRepository,
            KnowledgeBaseGrantRepository grantRepository,
            UserRepository userRepository) {
        this.groupRepository = groupRepository;
        this.userGroupRepository = userGroupRepository;
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public GroupView createGroup(CreateGroupRequest request, Viewer viewer) {
        Group g = new Group();
        g.setId(UUID.randomUUID());
        g.setName(request.name());
        g.setCreatedBy(viewer.userId());
        em.persist(g);

        Set<UUID> members = new HashSet<>();
        members.add(viewer.userId()); // 创建者自动成为成员
        if (request.memberUserIds() != null) {
            members.addAll(request.memberUserIds());
        }
        for (UUID uid : members) {
            if (!userRepository.existsById(uid)) {
                continue;
            }
            UserGroup ug = new UserGroup();
            ug.setId(UUID.randomUUID());
            ug.setGroupId(g.getId());
            ug.setUserId(uid);
            em.persist(ug);
        }
        return toView(g, viewer.userId());
    }

    public ListGroupsResponse listGroups(Viewer viewer) {
        List<GroupSummary> groups = summariesForGranting(viewer);
        return new ListGroupsResponse(groups);
    }

    public GroupView getGroup(UUID groupId, Viewer viewer) {
        Group g = requireManageable(groupId, viewer);
        return toView(g, viewer.userId());
    }

    @Transactional
    public GroupView addMember(UUID groupId, AddMemberRequest request, Viewer viewer) {
        Group g = requireManageable(groupId, viewer);
        if (!userRepository.existsById(request.userId())) {
            throw ApiException.notFound("用户不存在");
        }
        if (userGroupRepository.findByGroupIdAndUserId(groupId, request.userId()).isEmpty()) {
            UserGroup ug = new UserGroup();
            ug.setId(UUID.randomUUID());
            ug.setGroupId(groupId);
            ug.setUserId(request.userId());
            em.persist(ug);
        }
        return toView(g, viewer.userId());
    }

    @Transactional
    public GroupView removeMember(UUID groupId, UUID userId, Viewer viewer) {
        Group g = requireManageable(groupId, viewer);
        userGroupRepository.deleteByGroupIdAndUserId(groupId, userId);
        return toView(g, viewer.userId());
    }

    @Transactional
    public void deleteGroup(UUID groupId, Viewer viewer) {
        requireManageable(groupId, viewer);
        // 清理指向该群组的全部知识库授权
        grantRepository.deleteByPrincipalTypeAndPrincipalId(GrantPrincipalType.GROUP, groupId);
        userGroupRepository.deleteByGroupId(groupId);
        groupRepository.deleteById(groupId);
    }

    /** 当前用户可授予（用于知识库授权选择）：管理端视角可见全部，其余仅自己创建或所属群组。 */
    public List<GroupSummary> summariesForGranting(Viewer viewer) {
        requireUser(viewer.userId());
        List<Group> groups;
        if (viewer.admin()) {
            groups = groupRepository.findAll();
        } else {
            Set<UUID> ids = new HashSet<>();
            groupRepository.findByCreatedBy(viewer.userId()).forEach(g -> ids.add(g.getId()));
            userGroupRepository.findByUserId(viewer.userId())
                    .forEach(ug -> ids.add(ug.getGroupId()));
            groups = new ArrayList<>(groupRepository.findAllById(ids));
        }
        return groups.stream()
                .map(g -> new GroupSummary(g.getId(), g.getName()))
                .sorted(Comparator.comparing(GroupSummary::name))
                .toList();
    }

    // ---- 内部辅助 ----

    /** 群组管理者 = 创建者或管理端视角（admin 标志由接口层按权限码裁决）。 */
    private Group requireManageable(UUID groupId, Viewer viewer) {
        Group g = groupRepository.findById(groupId)
                .orElseThrow(() -> ApiException.notFound("群组不存在"));
        requireUser(viewer.userId());
        boolean creator = g.getCreatedBy().equals(viewer.userId());
        if (!viewer.admin() && !creator) {
            throw ApiException.forbidden("仅群组创建者或管理员可管理该群组");
        }
        return g;
    }

    private GroupView toView(Group g, UUID currentUserId) {
        List<GroupMemberView> members = userGroupRepository.findByGroupId(g.getId()).stream()
                .map(ug -> {
                    String name = userRepository.findById(ug.getUserId())
                            .map(u -> (u.getName() != null && !u.getName().isBlank())
                                    ? u.getName() : u.getUsername())
                            .orElseGet(() -> ug.getUserId().toString());
                    return new GroupMemberView(ug.getUserId(), name);
                })
                .sorted(Comparator.comparing(GroupMemberView::userName))
                .toList();
        String creatorName = userRepository.findById(g.getCreatedBy())
                .map(u -> (u.getName() != null && !u.getName().isBlank()) ? u.getName() : u.getUsername())
                .orElseGet(() -> g.getCreatedBy().toString());
        return new GroupView(
                g.getId(), g.getName(), g.getCreatedBy(), creatorName, members, g.getCreatedAt());
    }

    private User requireUser(UUID currentUserId) {
        return userRepository.findById(currentUserId)
                .orElseThrow(() -> ApiException.unauthorized("用户不存在或已失效"));
    }
}
