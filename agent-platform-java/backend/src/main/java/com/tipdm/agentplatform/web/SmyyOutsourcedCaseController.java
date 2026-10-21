package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.SmyyOutsourcedCase;
import com.tipdm.agentplatform.service.PermissionCodeCache;
import com.tipdm.agentplatform.service.SmyyCaseContentResolver;
import com.tipdm.agentplatform.service.SmyyOutsourcedCaseService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/smyy")
public class SmyyOutsourcedCaseController {

    private final SmyyOutsourcedCaseService service;
    private final SmyyCaseContentResolver contentResolver;

    public SmyyOutsourcedCaseController(SmyyOutsourcedCaseService service,
                                        SmyyCaseContentResolver contentResolver) {
        this.service = service;
        this.contentResolver = contentResolver;
    }

    @GetMapping("/cases")
    public CaseListResponse publicCases() {
        List<CaseView> items = service.list().stream().map(this::view).toList();
        return new CaseListResponse(items, items.size());
    }

    @GetMapping("/cases/{protoId}")
    public CaseView publicCase(@PathVariable String protoId) {
        return view(service.get(protoId));
    }

    @GetMapping("/admin/cases")
    @RequirePermission(PermissionCodeCache.ADMIN_ACCESS)
    public CaseListResponse adminCases() {
        List<CaseView> items = service.list().stream().map(this::view).toList();
        return new CaseListResponse(items, items.size());
    }

    @PostMapping("/admin/cases")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodeCache.ADMIN_ACCESS)
    public CaseView upsert(@RequestBody Map<String, Object> request) {
        return view(service.upsert(request));
    }

    @PutMapping("/admin/cases/{protoId}/sections/{section}")
    @RequirePermission(PermissionCodeCache.ADMIN_ACCESS)
    public CaseView replaceSection(@PathVariable String protoId,
                                   @PathVariable String section,
                                   @RequestBody SectionRequest request) {
        return view(service.replaceSection(protoId, section, request.content()));
    }

    private CaseView view(SmyyOutsourcedCase entity) {
        return CaseView.from(entity, contentResolver);
    }

    @DeleteMapping("/admin/cases/{protoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionCodeCache.ADMIN_ACCESS)
    public void delete(@PathVariable String protoId) {
        service.delete(protoId);
    }

    public record CaseListResponse(List<CaseView> items, int total) {
    }

    public record SectionRequest(String content) {
    }

    /**
     * 对外视图。内容字段（{@code guideHtml} 等）由 {@code contentResolver} 按库里的相对路径读文件回填，
     * 路径字段（{@code guideFileName} 等）原样输出——契约与改造前完全一致，前端无需改动。
     */
    public record CaseView(
            UUID id,
            String protoId,
            String slug,
            String title,
            String tag,
            String category,
            String source,
            String status,
            String guideHtml,
            String animationHtml,
            String animationSegmentsJson,
            String codeText,
            String resultHtml,
            String replayHtml,
            String guideFileName,
            String guideFilesJson,
            String animationFileName,
            String codeFileName,
            String codeFilesJson,
            String resultFileName,
            String resultFilesJson,
            String replayFileName,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {
        static CaseView from(SmyyOutsourcedCase c, SmyyCaseContentResolver resolver) {
            SmyyCaseContentResolver.Content content = resolver.resolve(c);
            return new CaseView(
                    c.getId(),
                    c.getProtoId(),
                    c.getSlug(),
                    c.getTitle(),
                    c.getTag(),
                    c.getCategory(),
                    c.getSource(),
                    c.getStatus(),
                    content.guideHtml(),
                    content.animationHtml(),
                    content.animationSegmentsJson(),
                    content.codeText(),
                    content.resultHtml(),
                    content.replayHtml(),
                    c.getGuideFileName(),
                    content.guideFilesJson(),
                    c.getAnimationFileName(),
                    c.getCodeFileName(),
                    content.codeFilesJson(),
                    c.getResultFileName(),
                    content.resultFilesJson(),
                    c.getReplayFileName(),
                    c.getVersion(),
                    c.getCreatedAt(),
                    c.getUpdatedAt()
            );
        }
    }
}
