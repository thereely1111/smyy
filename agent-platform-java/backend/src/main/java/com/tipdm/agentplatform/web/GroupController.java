package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.GroupService;
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
    public GroupView create(
            @Valid @RequestBody CreateGroupRequest body, HttpServletRequest request) {
        return service.createGroup(body, accessGuard.viewer(request));
    }

    @GetMapping
    public ListGroupsResponse list(HttpServletRequest request) {
        return service.listGroups(accessGuard.viewer(request));
    }

    @GetMapping("/{id}")
    public GroupView get(@PathVariable UUID id, HttpServletRequest request) {
        return service.getGroup(id, accessGuard.viewer(request));
    }

    @PostMapping(value = "/{id}/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public GroupView addMember(
            @PathVariable UUID id,
            @Valid @RequestBody AddMemberRequest body,
            HttpServletRequest request) {
        return service.addMember(id, body, accessGuard.viewer(request));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @PathVariable UUID id, @PathVariable UUID userId, HttpServletRequest request) {
        service.removeMember(id, userId, accessGuard.viewer(request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest request) {
        service.deleteGroup(id, accessGuard.viewer(request));
    }
}
