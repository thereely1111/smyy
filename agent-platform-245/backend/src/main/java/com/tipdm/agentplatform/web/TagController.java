package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.TagService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.dto.TagDtos.TagListResponse;
import com.tipdm.agentplatform.web.dto.TagDtos.TagRequest;
import com.tipdm.agentplatform.web.dto.TagDtos.TagView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 标签接口（2026-09-20）。
 *
 * <p>两组端点：</p>
 * <ul>
 *   <li>{@code GET /api/tags} —— 公开标签池（登录即可），只回启用标签，供
 *       广场 / 知识库列表的筛选 chips 与表单下拉；</li>
 *   <li>{@code /api/admin/tags} —— 管理端 CRUD（建 / 改 / 停用 / 排序 / 删除，
 *       含两侧使用计数），2026-09-21 起按 {@code tag:*} 细粒度码收口
 *       （GET→tag:menu、POST→tag:create、PUT→tag:update、DELETE→tag:delete，
 *       原 admin:access 收口已拆分，与资源权限清单对齐）。</li>
 * </ul>
 */
@RestController
public class TagController {

    private final TagService service;

    public TagController(TagService service) {
        this.service = service;
    }

    /** 公开标签池：只回 enabled，精简视图（id/name/color）。 */
    @GetMapping("/api/tags")
    public TagListResponse publicTags() {
        List<TagView> tags = service.listPublic();
        return new TagListResponse(tags, tags.size());
    }

    @GetMapping("/api/admin/tags")
    @RequirePermission("tag:menu")
    public TagListResponse adminTags() {
        List<TagView> tags = service.listAdmin();
        return new TagListResponse(tags, tags.size());
    }

    @PostMapping("/api/admin/tags")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("tag:create")
    public TagView create(@Valid @RequestBody TagRequest request) {
        return service.create(request);
    }

    @PutMapping("/api/admin/tags/{id}")
    @RequirePermission("tag:update")
    public TagView update(@PathVariable UUID id, @RequestBody TagRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/api/admin/tags/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("tag:delete")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
