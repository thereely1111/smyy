package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.GroupService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.dto.GroupDtos.AddMemberRequest;
import com.tipdm.agentplatform.web.dto.GroupDtos.CreateGroupRequest;
import com.tipdm.agentplatform.web.dto.GroupDtos.GroupView;
import com.tipdm.agentplatform.web.dto.GroupDtos.ListGroupsResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 群组接口。
 *
 * <p>群组是「按群组批量授权」的载体，被知识库授权（{@code /api/knowledge-bases} 的 grants
 * 端点）与资料库成员授权（{@code /api/library/spaces} 的 members 端点）共用。
 * 属性能改动、成员能增减，因此所有端点按 {@code group:*} 细粒度码收口
 * （与 {@code tag:*} 同构）：GET→group:menu、建组→group:create、
 * 成员与删除→group:update。</p>
 *
 * <p><b>码未开通时全部端点对所有账号（含 admin）返 403</b>，切面级无豁免——
 * 与其他受管模块一致。授权现场（成员管理 / 知识库授权面板）需要的
 * 「可授予群组清单」由各自模块的响应内嵌提供，不额外暴露无码端点给这些页面兜底，
 * 避免在授权之外再叠一道与授权语义无关的门槛。</p>
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService service;
    private final SessionAccessGuard accessGuard;

    public GroupController(GroupService service, SessionAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("group:create")
    public GroupView create(
            @Valid @RequestBody CreateGroupRequest body, HttpServletRequest request) {
        return service.createGroup(body, accessGuard.viewer(request));
    }

    @GetMapping
    @RequirePermission("group:menu")
    public ListGroupsResponse list(HttpServletRequest request) {
        return service.listGroups(accessGuard.viewer(request));
    }

    @GetMapping("/{id}")
    @RequirePermission("group:menu")
    public GroupView get(@PathVariable UUID id, HttpServletRequest request) {
        return service.getGroup(id, accessGuard.viewer(request));
    }

    @PostMapping(value = "/{id}/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("group:update")
    public GroupView addMember(
            @PathVariable UUID id,
            @Valid @RequestBody AddMemberRequest body,
            HttpServletRequest request) {
        return service.addMember(id, body, accessGuard.viewer(request));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("group:update")
    public void removeMember(
            @PathVariable UUID id, @PathVariable UUID userId, HttpServletRequest request) {
        service.removeMember(id, userId, accessGuard.viewer(request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("group:update")
    public void delete(@PathVariable UUID id, HttpServletRequest request) {
        service.deleteGroup(id, accessGuard.viewer(request));
    }
}
