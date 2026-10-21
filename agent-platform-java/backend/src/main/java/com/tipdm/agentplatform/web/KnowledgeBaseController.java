package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.KnowledgeBase;
import com.tipdm.agentplatform.domain.KnowledgeBaseScope;
import com.tipdm.agentplatform.service.KnowledgeBaseService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.CreateKnowledgeBaseRequest;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.GrantRequest;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.GrantView;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.KnowledgeBaseView;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.ListGrantsResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.ListKnowledgeBasesResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.UpdateKnowledgeBaseRequest;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.UploadKnowledgeDocumentResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.ListKnowledgeDocumentStatusResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.ListKnowledgeDocumentsResponse;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.SearchKnowledgeBaseRequest;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.SearchKnowledgeBaseResponse;
import com.tipdm.agentplatform.web.error.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    /** 单份知识库文档大小上限（字节），与 AttachmentController.MAX_ATTACH_BYTES 对齐。 */
    private static final long MAX_DOCUMENT_BYTES = 20L * 1024 * 1024;

    private final KnowledgeBaseService service;
    private final SessionAccessGuard accessGuard;

    public KnowledgeBaseController(KnowledgeBaseService service, SessionAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    /**
     * 集合端点同时接受带/不带尾斜杠 —— 端点基路径统一为 `/api/knowledge-bases`
     * （Java/Spring 风格 kebab-case，与设计文档《知识库管理模块 — 技术方案》
     * 第 8 节 API 契约一致）。Spring Boot 3.x 起默认关闭尾斜杠匹配，因此同时
     * 映射带/不带尾斜杠两种写法，避免契约里的调用落到静态资源处理器上报 404。
     */
    @GetMapping({"", "/"})
    public ListKnowledgeBasesResponse list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.util.UUID tag,
            @org.springframework.web.bind.annotation.RequestParam(required = false) KnowledgeBaseScope scope,
            HttpServletRequest request) {
        return service.list(accessGuard.viewer(request), tag, scope);
    }

    @GetMapping("/{id}")
    public KnowledgeBaseView get(@PathVariable UUID id, HttpServletRequest request) {
        return service.get(id, accessGuard.viewer(request));
    }

    /**
     * 个人库创建：scope 由路由固定为 PERSONAL，不经请求体（防变造），任何登录用户可建。
     */
    @PostMapping({"", "/"})
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBaseView create(
            @Valid @RequestBody CreateKnowledgeBaseRequest body, HttpServletRequest request) {
        return service.create(body, accessGuard.viewer(request));
    }

    /**
     * 平台库创建：scope 由路由固定为 PLATFORM；knowledge:create 为静态门槛，
     * 走 {@code @RequirePermission} 切面（与 {@code credential:create} 平台创建同模式）。
     * （2026-09-22 权限码定版：原 knowledge:manage 拆为 create/update/delete。）
     */
    @PostMapping("/platform")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("knowledge:create")
    public KnowledgeBaseView createPlatform(
            @Valid @RequestBody CreateKnowledgeBaseRequest body, HttpServletRequest request) {
        return service.createPlatform(body, accessGuard.viewer(request));
    }

    /** 编辑：按实体 scope 分流——平台库要求 knowledge:update；个人库靠 WRITE 数据授权。 */
    @PutMapping("/{id}")
    public KnowledgeBaseView update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateKnowledgeBaseRequest body,
            HttpServletRequest request) {
        Viewer viewer = accessGuard.viewer(request);
        requirePlatformCodeIfNeeded(id, viewer, request, "knowledge:update");
        return service.update(id, body, viewer);
    }

    /** 删除：分流规则同 {@link #update}（平台库 knowledge:delete）。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest request) {
        Viewer viewer = accessGuard.viewer(request);
        requirePlatformCodeIfNeeded(id, viewer, request, "knowledge:delete");
        service.delete(id, viewer);
    }

    /**
     * 条件码门槛：仅当目标知识库是平台库（scope=PLATFORM）时要求对应权限码
     * （静态注解表达不了「按资源 scope 分支」，与 SkillController /
     * ModelCredentialController 的 requirePlatformCodeIfNeeded 同模式）。
     * 个人库的可见性判定（无任何授权 403）已由 requireVisible 拦下。
     */
    private void requirePlatformCodeIfNeeded(
            UUID id, Viewer viewer, HttpServletRequest request, String code) {
        KnowledgeBase kb = service.requireVisible(id, viewer);
        if (kb.getScope() == KnowledgeBaseScope.PLATFORM) {
            accessGuard.requirePermissionCode(request, code);
        }
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadKnowledgeDocumentResponse uploadDocument(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("上传文件为空");
        }
        // 显式单文件上限（与附件上传的 20MB 对齐；全局 multipart 上限见 application.yml）。
        // 摄入路径会把整份文件读进内存再分块，仅有全局上限时错误信息也不指向业务限额。
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw ApiException.badRequest(
                    "文档过大（" + file.getSize() + " 字节），单文件上限 "
                            + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB");
        }
        return service.uploadDocument(
                id, file.getOriginalFilename(), readStream(file), accessGuard.viewer(request));
    }

    /** 文档清单，按创建时间倒序。 */
    @GetMapping("/{id}/documents")
    public ListKnowledgeDocumentsResponse listDocuments(
            @PathVariable UUID id, HttpServletRequest request) {
        return service.listDocuments(id, accessGuard.viewer(request));
    }

    @GetMapping("/{id}/documents/status")
    public ListKnowledgeDocumentStatusResponse documentStatus(
            @PathVariable UUID id,
            @RequestParam(name = "ids", required = false) List<UUID> ids,
            HttpServletRequest request) {
        return service.getDocumentStatus(id, ids, accessGuard.viewer(request));
    }

    @DeleteMapping("/{id}/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(
            @PathVariable UUID id, @PathVariable UUID docId, HttpServletRequest request) {
        service.deleteDocument(id, docId, accessGuard.viewer(request));
    }

    @GetMapping("/{id}/grants")
    public ListGrantsResponse listGrants(@PathVariable UUID id, HttpServletRequest request) {
        return service.listGrants(id, accessGuard.viewer(request));
    }

    @PostMapping("/{id}/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public GrantView createGrant(
            @PathVariable UUID id,
            @Valid @RequestBody GrantRequest body,
            HttpServletRequest request) {
        return service.createGrant(id, body, accessGuard.viewer(request));
    }

    @DeleteMapping("/{id}/grants/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGrant(
            @PathVariable UUID id, @PathVariable UUID grantId, HttpServletRequest request) {
        service.deleteGrant(id, grantId, accessGuard.viewer(request));
    }

    @PostMapping("/{id}/search")
    public SearchKnowledgeBaseResponse search(
            @PathVariable UUID id,
            @Valid @RequestBody SearchKnowledgeBaseRequest body,
            HttpServletRequest request) {
        return service.search(id, body.query(), body.topK(), accessGuard.viewer(request));
    }

    private java.io.InputStream readStream(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("读取上传文件失败: " + e.getMessage());
        }
    }
}
