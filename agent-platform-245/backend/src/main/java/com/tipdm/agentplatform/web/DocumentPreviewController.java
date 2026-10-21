package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.config.DocumentPreviewProperties;
import com.tipdm.agentplatform.domain.LibraryNode;
import com.tipdm.agentplatform.domain.LibraryNodeKind;
import com.tipdm.agentplatform.service.DocumentPreviewClient;
import com.tipdm.agentplatform.service.LibraryAccessService;
import com.tipdm.agentplatform.service.LibraryBlobStore;
import com.tipdm.agentplatform.service.PreviewTicketService;
import com.tipdm.agentplatform.service.SessionLineageService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 文档预览的 HTTP 入口：一条供<b>前端</b>（拿预览地址）、一条供<b>预览服务</b>（回拉文件字节）、
 * 一条供<b>前端</b>（读开关）。
 *
 * <p>各链路的信任级别完全不同，务必区分：
 * <ul>
 *   <li>{@code /nodes/{nodeId}/preview}——浏览器调用，<b>带用户身份</b>，
 *       走 {@code AccessGuard} 判权；</li>
 *   <li>{@code /api/doc-preview/raw}——预览服务匿名调用（它不会带 access token），
 *       仅凭<b>一次性短时票据</b>放行，因此票据必须是严格降权的窄门；</li>
 *   <li>{@code /api/config/document-preview}——普通登录读，<b>不含任何用户数据</b>，
 *       只把「预览服务开没开」这个开关下发给前端。</li>
 * </ul>
 *
 * <p>三条链路放在同一个类里，是因为它们共享同一份配置与同一个外部服务。</p>
 */
@RestController
public class DocumentPreviewController {

    private static final Logger log = LoggerFactory.getLogger(DocumentPreviewController.class);

    private final SessionAccessGuard accessGuard;
    private final LibraryAccessService accessService;
    private final LibraryBlobStore blobStore;
    private final PreviewTicketService ticketService;
    private final DocumentPreviewClient client;
    private final DocumentPreviewProperties properties;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionLineageService sessionLineageService;

    public DocumentPreviewController(SessionAccessGuard accessGuard,
                                     LibraryAccessService accessService,
                                     LibraryBlobStore blobStore,
                                     PreviewTicketService ticketService,
                                     DocumentPreviewClient client,
                                     DocumentPreviewProperties properties,
                                     SessionWorkspaceManager sessionWorkspaceManager,
                                     SessionLineageService sessionLineageService) {
        this.accessGuard = accessGuard;
        this.accessService = accessService;
        this.blobStore = blobStore;
        this.ticketService = ticketService;
        this.client = client;
        this.properties = properties;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.sessionLineageService = sessionLineageService;
    }

    /**
     * 为资料库文件生成预览地址（供前端 iframe 使用）。
     *
     * <p>判权口径与 {@code /nodes/{nodeId}/content} 一致（同一 {@code requireVisibleNode}）。</p>
     *
     * @param nodeId  资料库节点 id
     * @param request 当前请求（用于取登录身份）
     * @return 可直接放进 iframe 的预览地址
     */
    @GetMapping("/api/library/nodes/{nodeId}/preview")
    public PreviewResponse preview(
            @PathVariable UUID nodeId, HttpServletRequest request) {
        if (!properties.enabled()) {
            throw ApiException.badRequest("文档预览功能未启用");
        }
        Viewer viewer = accessGuard.viewer(request);
        LibraryNode node = accessService.requireVisibleNode(nodeId, viewer);
        if (node.getKind() != LibraryNodeKind.drive) {
            throw ApiException.badRequest("只有文件类型才能预览");
        }
        String rel = node.getContentRel();
        if (rel == null || rel.isBlank()) {
            throw ApiException.notFound("文件不存在");
        }

        // 先探活：服务不可用时不必签票（票据单次消费，白签浪费）
        try {
            client.ensureAvailable();
        } catch (IllegalStateException e) {
            // 转成 400：这是部署/配置层面的可预期问题，不该以 500 呈现给前端
            throw ApiException.badRequest(
                    "文档预览服务不可用，请稍后重试或下载后查看");
        }

        // 票据带上原始文件名：存量 drive 的落盘名可能已丢扩展名，
        // 预览服务靠后缀判断渲染方式，只给落盘名会让它「认不出格式」。
        // 三级兜底：sidecar 原名 → 节点标题（用户上传时的名字，带后缀）→ 落盘名。
        String fileName = blobStore.driveOriginalName(node.getSpaceId(), rel);
        if (node.getTitle() != null && !node.getTitle().isBlank()
                && extensionOf(fileName).isEmpty()) {
            fileName = node.getTitle();
        }
        String ticket = ticketService.issue(node.getSpaceId(), rel, fileName);
        String sourceUrl = properties.publicBaseUrl() + "/api/doc-preview/raw?ticket=" + ticket;
        String previewUrl = client.buildPreviewUrl(sourceUrl, fileName);
        return new PreviewResponse(nodeId, previewUrl);
    }

    /**
     * <b>供预览服务回拉文件字节</b>：凭一次性票据放行，不认用户身份。
     *
     * <p>⚠️ 这是本平台<b>有意对外暴露</b>的端点，安全边界全在票据上：
     * <ul>
     *   <li><b>单次</b>——Redis SETNX 记录已消费，重放只有第一次能过；</li>
     *   <li><b>短时效</b>——默认 300秒；</li>
     *   <li><b>绑定单文件</b>——票据里锁定空间与相对路径，换路径直接验签不过。</li>
     * </ul>
     *
     * <p>⚠️ <b>不要把本路径加入 {@code JwtAuthFilter.PUBLIC_PATHS}</b>——那是<b>前缀级
     * 无条件放行</b>（{@code path.startsWith(publicPath + "/")}），会让该前缀下所有请求
     * 都脱离认证过滤。本端点靠 {@code shouldNotFilter} 里「路径匹配 + 确有 ticket 参数」
     * 的精确条件放行，与产物下载票据通道同一套路。</p>
     *
     * @param ticket 一次性回拉票据（由签发方绑定到具体空间与相对路径）
     * @return 文件字节流
     */
    @GetMapping("/api/doc-preview/raw")
    public ResponseEntity<byte[]> raw(
            @RequestParam("ticket") String ticket) {
        PreviewTicketService.Payload payload = ticketService.consume(ticket)
                .orElseThrow(() -> ApiException.badRequest("预览票据无效、已过期或已被使用"));
        byte[] bytes = readBytes(payload);

        // 响应头只给类型提示，不强制 attachment：预览服务按后缀自行判断渲染方式，
        // 强制 attachment 会让部分实现（图片直链）走错分支
        String encoded = URLEncoder.encode(fileNameOf(payload), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentLength(bytes.length)
                .body(bytes);
    }

    /**
     * 按票据来源取文件字节。
     *
     * <p>两类来源的寻址方式不兼容，因此按 {@link PreviewTicketService.Payload.Source}
     * 分派：资料库走 {@code blobStore}，会话产物走 {@code SessionArtifactController} 的
     * 静态解析——后者自带子会话白名单与真实路径（符号链接）校验，
     * <b>不能</b>在这里另写一份「拼路径再读」的逻辑。</p>
     *
     * @param payload 已核销的票据载荷
     * @return 文件字节
     */
    private byte[] readBytes(PreviewTicketService.Payload payload) {
        if (payload.source() instanceof PreviewTicketService.Payload.ArtifactSource artifact) {
            return readArtifactBytes(artifact, payload.relativePath());
        }
        PreviewTicketService.Payload.LibrarySource library =
                (PreviewTicketService.Payload.LibrarySource) payload.source();
        return blobStore.readDriveFile(UUID.fromString(library.spaceId()), payload.relativePath());
    }

    /**
     * 读取会话产物文件的字节。
     *
     * <p>归属校验<b>不在此处</b>：票据是签发时判权后的窄权限凭据，回拉由预览服务匿名发起、
     * 无登录态可判。因此本方法只负责「按票据给的坐标取字节」，解析失败统一收敛为 404
     * （不区分「不存在」与「越权」，与产物其它端点口径一致）。</p>
     *
     * <p><b>子会话白名单按票据里的 userId 现查</b>，不缓存也不置空：产物路径可能以
     * 子会话 id 为首段，置空会让「面板里点得开、预览服务却永远拉不到」——
     * 签票成功而回拉 404，比直接拒绝更难排查。这里沿用 {@code DownloadTicketService}
     * 的同一惯例（票据带 userId，回拉端点据此还原上下文）。</p>
     *
     * @param artifact     票据绑定的智能体、会话与发起人
     * @param relativePath 会话内相对路径
     * @return 文件字节
     */
    private byte[] readArtifactBytes(PreviewTicketService.Payload.ArtifactSource artifact,
                                    String relativePath) {
        String agentId = artifact.agentId().toString();
        Path sessionRoot = sessionWorkspaceManager.resolveSessionDir(agentId, artifact.sessionId());
        Set<String> subSessions = artifact.userId().isBlank()
                ? Set.of()
                : new LinkedHashSet<>(sessionLineageService.subSessionsOf(artifact.userId(), artifact.sessionId()));
        Path target = SessionArtifactController.resolveArtifactFile(
                agentId, artifact.sessionId(), sessionRoot, subSessions, relativePath);
        if (target == null) {
            throw ApiException.notFound("产物文件不存在");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "PREVIEW_READ_FAILED", "产物文件读取失败");
        }
    }

    /**
     * 预览服务是否启用：前端据此决定「一律交给预览服务」还是「回落到本地文本渲染」。
     *
 * <p>只下发开关，<b>不下发格式名单</b>——格式能否渲染由预览服务自己判定，
 * 平台侧再维护一份只会与它漂移，而漂移的表现是「这边以为能预览、
 * 那边按不支持拒掉」，且不报任何错。</p>
     *
 * <p>本端点<b>不含任何用户数据</b>，只暴露一个开关，因此不需要判权——
 * 挂在 {@code /api/config/**} 与其他配置接口同域。</p>
     *
 * @return 能力开关视图
     */
    @GetMapping("/api/config/document-preview")
    public DocumentPreviewView documentPreview() {
        return new DocumentPreviewView(properties.enabled());
    }

    /**
     * @param enabled 预览服务是否启用（关闭时前端应回落到本地文本渲染）
     */
    public record DocumentPreviewView(boolean enabled) {
    }

    /**
     * 回拉时对外暴露的文件名。
     *
     * <p><b>优先用票据里的原名</b>：落盘名经过 ASCII 化，中文名会被压成
     * {@code uuid-file} 这类形态——早期版本的落盘名规则还会连扩展名一起丢掉，
     * 导致预览服务「认不出格式」而报不支持。预览服务完全依赖后缀判断渲染方式，
     * 因此这里必须给出带后缀的真实文件名。</p>
     *
     * <p>原名缺失时回落盘名，保证响应头不为空。</p>
     *
     * @param payload 已核销的票据载荷
     * @return 带扩展名的文件名
     */
    private static String fileNameOf(PreviewTicketService.Payload payload) {
        String original = payload.fileName();
        if (original != null && !original.isBlank()) {
            return original;
        }
        String rel = payload.relativePath();
        int idx = rel.lastIndexOf('/');
        return idx >= 0 ? rel.substring(idx + 1) : rel;
    }

    /**
     * 取小写扩展名。
     *
     * @param fileName 文件名
     * @return 扩展名（含点，如 {@code .docx}）；无扩展名时返回空串
     */
    private static String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 预览地址响应。
     *
     * @param nodeId     资料库节点 id
     * @param previewUrl 可直接放进 iframe 的预览地址
     */
    public record PreviewResponse(UUID nodeId, String previewUrl) {
    }
}
