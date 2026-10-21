package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.Tool;
import com.tipdm.agentplatform.repository.ToolRepository;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.ToolDtos.ToolRequest;
import com.tipdm.agentplatform.web.dto.ToolDtos.ToolResponse;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
public class ToolService {

    private final ToolRepository repository;
    private final AgentAssemblyService assemblyService;

    public ToolService(ToolRepository repository, AgentAssemblyService assemblyService) {
        this.repository = repository;
        this.assemblyService = assemblyService;
    }

    public PageResponse<ToolResponse> list(String search, ResourceStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<Tool> result;
        if (StringUtils.hasText(search) && status != null) {
            result = repository.findByNameContainingIgnoreCaseAndStatus(search.trim(), status, pageable);
        } else if (StringUtils.hasText(search)) {
            result = repository.findByNameContainingIgnoreCase(search.trim(), pageable);
        } else if (status != null) {
            result = repository.findByStatus(status, pageable);
        } else {
            result = repository.findAll(pageable);
        }
        return new PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    public ToolResponse get(UUID id) {
        return toResponse(requireTool(id));
    }

    @Transactional
    public ToolResponse create(ToolRequest request) {
        if (repository.existsByNameIgnoreCase(request.name().trim())) {
            throw ApiException.conflict("工具名称已存在: " + request.name());
        }
        Tool tool = new Tool();
        apply(tool, request);
        ToolResponse response = toResponse(repository.save(tool));
        assemblyService.evictAll();
        return response;
    }

    @Transactional
    public ToolResponse update(UUID id, ToolRequest request) {
        Tool tool = requireTool(id);
        repository.findByNameIgnoreCase(request.name().trim())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("工具名称已存在: " + request.name());
                });
        apply(tool, request);
        return toResponse(repository.save(tool));
    }

    @Transactional
    public ToolResponse updateStatus(UUID id, ResourceStatus status) {
        Tool tool = requireTool(id);
        tool.setStatus(status);
        ToolResponse response = toResponse(repository.save(tool));
        assemblyService.evictAll();
        return response;
    }

    @Transactional
    public void delete(UUID id) {
        requireTool(id);
        repository.deleteById(id);
        assemblyService.evictAll();
    }

    private Tool requireTool(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("工具不存在: " + id));
    }

    private void apply(Tool tool, ToolRequest request) {
        tool.setName(request.name().trim());
        tool.setDescription(request.description());
        tool.setToolType(StringUtils.hasText(request.toolType()) ? request.toolType().trim() : "mcp");
        tool.setExecutionMode(request.executionMode());
        tool.setAskConfirmation(request.askConfirmation());
        tool.setInputSchema(request.inputSchema());
        tool.setMcpEndpoint(request.mcpEndpoint());
        tool.setStatus(request.status() == null ? ResourceStatus.active : request.status());
    }

    private ToolResponse toResponse(Tool tool) {
        return new ToolResponse(
                tool.getId(),
                tool.getName(),
                tool.getDescription(),
                tool.getToolType(),
                tool.getExecutionMode(),
                tool.isAskConfirmation(),
                tool.getInputSchema(),
                tool.getMcpEndpoint(),
                tool.getStatus(),
                tool.getCreatedAt(),
                tool.getUpdatedAt());
    }
}
