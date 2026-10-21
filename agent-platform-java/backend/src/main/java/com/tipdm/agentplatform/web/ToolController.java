package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.service.ToolService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.CommonDtos.StatusRequest;
import com.tipdm.agentplatform.web.dto.ToolDtos.ToolRequest;
import com.tipdm.agentplatform.web.dto.ToolDtos.ToolResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolService service;

    public ToolController(ToolService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ToolResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ResourceStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(search, status, page, size);
    }

    @GetMapping("/{id}")
    public ToolResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("tool:create")
    public ToolResponse create(@Valid @RequestBody ToolRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission("tool:update")
    public ToolResponse update(@PathVariable UUID id, @Valid @RequestBody ToolRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @RequirePermission("tool:status")
    public ToolResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.updateStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("tool:delete")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
