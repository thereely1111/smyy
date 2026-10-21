package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.config.UploadPolicyProperties;
import com.tipdm.agentplatform.service.LibraryAccessService;
import com.tipdm.agentplatform.service.LibraryContentService;
import com.tipdm.agentplatform.service.LibraryMemberService;
import com.tipdm.agentplatform.service.LibraryNodeService;
import com.tipdm.agentplatform.service.LibrarySpaceService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.dto.LibraryDtos.CreateNodeRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.CreateSpaceRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.GrantMemberRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.ListMembersResponse;
import com.tipdm.agentplatform.web.dto.LibraryDtos.ListNodesResponse;
import com.tipdm.agentplatform.web.dto.LibraryDtos.ListSpacesResponse;
import com.tipdm.agentplatform.web.dto.LibraryDtos.MemberView;
import com.tipdm.agentplatform.web.dto.LibraryDtos.MoveNodeRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.NodeContentView;
import com.tipdm.agentplatform.web.dto.LibraryDtos.NodeRefView;
import com.tipdm.agentplatform.web.dto.LibraryDtos.NodeView;
import com.tipdm.agentplatform.web.dto.LibraryDtos.RenameNodeRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.SpaceView;
import com.tipdm.agentplatform.web.dto.LibraryDtos.UpdateNodeRequest;
import com.tipdm.agentplatform.web.dto.LibraryDtos.UploadDriveFileResponse;
import com.tipdm.agentplatform.web.dto.LibraryDtos.WriteDocRequest;
import com.tipdm.agentplatform.web.error.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 资料库接口。空间与节点两级资源，操作全部经
 * {@link SessionAccessGuard#viewer} 裁决调用者，资源级 ACL 在 service 层完成。
 *
 * <p>路径前缀 {@code /api/library}：与工作台 {@code /api/**} 其余接口同在
 * {@code JwtAuthFilter} 强制登录范围内，无需额外登记白名单。</p>
 *
 * <p><b>权限码分工</b>：查看与使用（读节点、引用、下载）不挂权限码，
 * 只走资源级 ACL——普通用户被授权进团队空间就该能看，挂了码反而多一道
 * 与授权重复的门槛；而「建团队空间 / 管团队空间」是<b>平台级治理动作</b>，
 * 用权限码做粗筛，再叠加资源级 owner 判定。</p>
 */
@RestController
@RequestMapping("/api/library")
public class LibraryController {

    /**
     * 建团队空间的权限码。
     *
     * <p>⚠️ 权限码由外部认证服务在登录时下发，本项目不能自签——
     * 需在外部服务开通后 {@code POST /api/library/spaces} 才对非授权账号放行，
     * 否则一律 403「缺少权限」。</p>
     */
    public static final String CREATE_SPACE = "library:space:create";

    /** 管团队空间（改设置 / 管成员 / 删空间）的权限码。开通要求同 {@link #CREATE_SPACE}。 */
    public static final String MANAGE_SPACE = "library:space:manage";

    private final LibrarySpaceService spaceService;
    private final LibraryNodeService nodeService;
    private final LibraryContentService contentService;
    private final LibraryAccessService accessService;
    private final LibraryMemberService memberService;
    private final SessionAccessGuard accessGuard;
    private final UploadPolicyProperties uploadPolicy;

    public LibraryController(LibrarySpaceService spaceService,
                             LibraryNodeService nodeService,
                             LibraryContentService contentService,
                             LibraryAccessService accessService,
                             LibraryMemberService memberService,
                             SessionAccessGuard accessGuard,
                             UploadPolicyProperties uploadPolicy) {
        this.spaceService = spaceService;
        this.nodeService = nodeService;
        this.contentService = contentService;
        this.accessService = accessService;
        this.memberService = memberService;
        this.accessGuard = accessGuard;
        this.uploadPolicy = uploadPolicy;
    }

    // ── 空间 ──────────────────────────────────────────────────────────

    @GetMapping("/spaces")
    public ListSpacesResponse listSpaces(HttpServletRequest request) {
        return spaceService.list(accessGuard.viewer(request));
    }

    /** 取个人空间，不存在则惰性创建——前端首屏无需先判存不存在。 */
    @GetMapping("/spaces/personal")
    public SpaceView personalSpace(HttpServletRequest request) {
        return spaceService.personal(accessGuard.viewer(request));
    }

    @GetMapping("/spaces/{spaceId}")
    public SpaceView getSpace(@PathVariable UUID spaceId, HttpServletRequest request) {
        return spaceService.get(spaceId, accessGuard.viewer(request));
    }

    @PostMapping("/spaces")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(CREATE_SPACE)
    public SpaceView createSpace(
            @Valid @RequestBody CreateSpaceRequest body, HttpServletRequest request) {
        return spaceService.create(body, accessGuard.viewer(request));
    }

    @PatchMapping("/spaces/{spaceId}")
    @RequirePermission(MANAGE_SPACE)
    public SpaceView updateSpace(
            @PathVariable UUID spaceId,
            @Valid @RequestBody CreateSpaceRequest body,
            HttpServletRequest request) {
        return spaceService.update(spaceId, body, accessGuard.viewer(request));
    }

    /**
     * 删团队空间：智能体配置与磁盘目录一并清理。
     * 个人空间不可删（它是惰性创建的固定入口）。
     */
    @DeleteMapping("/spaces/{spaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(MANAGE_SPACE)
    public void deleteSpace(@PathVariable UUID spaceId, HttpServletRequest request) {
        spaceService.delete(spaceId, accessGuard.viewer(request));
    }

    // ── 空间成员 ──────────────────────────────────────────────────────────
    /**
     * 空间成员列表 + 可授予群组。
     *
     * <p>门禁用 {@code MANAGE_SPACE} 而非资源级 owner 判定：两者都要过，
     * 权限码是粗筛（没开通就不让进），owner 判定才是资源级精确判据。</p>
     */
    @GetMapping("/spaces/{spaceId}/members")
    @RequirePermission(MANAGE_SPACE)
    public ListMembersResponse listMembers(
            @PathVariable UUID spaceId, HttpServletRequest request) {
        return memberService.listMembers(spaceId, accessGuard.viewer(request));
    }

    @PostMapping("/spaces/{spaceId}/members")
    @RequirePermission(MANAGE_SPACE)
    public MemberView grantMember(
            @PathVariable UUID spaceId,
            @Valid @RequestBody GrantMemberRequest body,
            HttpServletRequest request) {
        return memberService.grant(spaceId, body, accessGuard.viewer(request));
    }

    @DeleteMapping("/spaces/{spaceId}/members/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(MANAGE_SPACE)
    public void revokeMember(
            @PathVariable UUID spaceId,
            @PathVariable UUID grantId,
            HttpServletRequest request) {
        memberService.revoke(spaceId, grantId, accessGuard.viewer(request));
    }

    // ── 节点 ──────────────────────────────────────────────────────────
    /**
     * 列子节点。{@code parentId} 省略即列空间根下节点——目录树一层的展开。
     */
    @GetMapping("/spaces/{spaceId}/nodes")
    public ListNodesResponse listNodes(
            @PathVariable UUID spaceId,
            @RequestParam(required = false) UUID parentId,
            HttpServletRequest request) {
        return nodeService.listChildren(spaceId, parentId, accessGuard.viewer(request));
    }

    @PostMapping("/spaces/{spaceId}/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeView createNode(
            @PathVariable UUID spaceId,
            @Valid @RequestBody CreateNodeRequest body,
            HttpServletRequest request) {
        return nodeService.create(spaceId, body, accessGuard.viewer(request));
    }

    @GetMapping("/nodes/{nodeId}")
    public NodeView getNode(@PathVariable UUID nodeId, HttpServletRequest request) {
        return nodeService.get(nodeId, accessGuard.viewer(request));
    }

    /**
     * 取节点的引用串，供对话「添加文件 → 资料库」注入消息用。
     * 返回 {@code library://node/<id>}，模型据此调用 library_read 回读。
     */
    @GetMapping("/nodes/{nodeId}/ref")
    public NodeRefView getNodeRef(@PathVariable UUID nodeId, HttpServletRequest request) {
        return nodeService.refOf(nodeId, accessGuard.viewer(request));
    }

    /**
     * 取节点正文（按 kind 分派）：doc 返回 markdown，drive 回文件信息并提示走下载端点，
     * knowledge 回提示改用 {@code knowledge_search}。
     *
     * <p>这是 {@code library://} 引用链路的 HTTP 侧入口，agent 的 {@code library_read}
     * 工具复用同一 service，判权口径一致。</p>
     */
    @GetMapping("/nodes/{nodeId}/content")
    public NodeContentView getNodeContent(@PathVariable UUID nodeId, HttpServletRequest request) {
        LibraryContentService.Content c = contentService.read(nodeId, accessGuard.viewer(request));
        return new NodeContentView(c.nodeId(), c.kind(), c.text(), c.truncated(),
                c.fileName(), c.fileSize(), c.hint());
    }

    /** 写 doc 正文（markdown）。 */
    @PutMapping("/nodes/{nodeId}/content")
    public NodeView writeDocContent(
            @PathVariable UUID nodeId,
            @Valid @RequestBody WriteDocRequest body,
            HttpServletRequest request) {
        return nodeService.writeDoc(nodeId, body.markdown(), accessGuard.viewer(request));
    }

    /** 下载 drive 文件字节（原名走 Content-Disposition 还原中文名）。 */
    @GetMapping("/nodes/{nodeId}/file")
    public ResponseEntity<byte[]> downloadDriveFile(
            @PathVariable UUID nodeId, HttpServletRequest request) {
        LibraryContentService.DriveFile f =
                contentService.readDriveFile(nodeId, accessGuard.viewer(request));
        String encoded = URLEncoder.encode(f.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentLength(f.bytes().length)
                .body(f.bytes());
    }

    /**
     * 上传 drive 文件：一步建节点 + 落盘，返回引用串。
     * 扩展名白名单与单文件上限取自 {@code agent-platform.upload-policy}。
     */
    @PostMapping("/spaces/{spaceId}/drive-files")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadDriveFileResponse uploadDriveFile(
            @PathVariable UUID spaceId,
            @RequestParam(value = "parentId", required = false) UUID parentId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("上传文件不能为空");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!uploadPolicy.isAllowed(name)) {
            throw ApiException.badRequest("不支持的文件类型：" + name);
        }
        long maxBytes = uploadPolicy.getDriveMaxBytes();
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("文件超过 " + (maxBytes / 1024 / 1024) + "MB 限制");
        }
        LibraryNodeService.DriveUploadResult r;
        try (InputStream in = file.getInputStream()) {
            r = nodeService.uploadDriveFile(spaceId, parentId, name, in,
                    file.getSize(), accessGuard.viewer(request));
        }
        return new UploadDriveFileResponse(r.nodeId(), r.title(), r.fileName(),
                r.fileSize(), r.ref());
    }

    @PostMapping("/nodes/{nodeId}/move")
    public NodeView moveNode(
            @PathVariable UUID nodeId,
            @RequestBody MoveNodeRequest body,
            HttpServletRequest request) {
        return nodeService.move(nodeId, body, accessGuard.viewer(request));
    }

    @PatchMapping("/nodes/{nodeId}")
    public NodeView updateNode(
            @PathVariable UUID nodeId,
            @Valid @RequestBody UpdateNodeRequest body,
            HttpServletRequest request) {
        return nodeService.updateExcerpt(nodeId, body.excerpt(), accessGuard.viewer(request));
    }

    @PostMapping("/nodes/{nodeId}/rename")
    public NodeView renameNode(
            @PathVariable UUID nodeId,
            @Valid @RequestBody RenameNodeRequest body,
            HttpServletRequest request) {
        return nodeService.rename(nodeId, body.title(), accessGuard.viewer(request));
    }

    /** 软删节点及其整棵子树。 */
    @DeleteMapping("/nodes/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNode(@PathVariable UUID nodeId, HttpServletRequest request) {
        nodeService.delete(nodeId, accessGuard.viewer(request));
    }
}



