package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.config.DocumentPreviewProperties;
import com.tipdm.agentplatform.service.DocumentPreviewClient;
import com.tipdm.agentplatform.service.PreviewTicketService;
import com.tipdm.agentplatform.service.SessionLineageService;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 会话产物的<b>版式化预览</b>入口：把产物文件交给文档预览服务（TipDM-FV）渲染成网页。
 *
 * <p><b>本端点不做任何格式判据</b>——包括 CAD、不支持类型、无扩展名。
 * 能否渲染完全由预览服务判定：它认得的类型走对应渲染器，认不得的
 * 渲染一个明确的「不支持该格式」页面。平台侧重复判断只会引入漂移，
 * 而漂移的表现是「这边以为能预览、那边按不支持拒掉」，<b>不报任何错</b>。</p>
 *
 * <p><b>与 {@code /artifacts/content} 的分工</b>：{@code content} 端点读文本，
 * 面向 markdown / 代码 / 日志这类要看内容的产物，且<b>不依赖外部服务</b>。
 * 两者不是二选一——前端在预览服务可用时一律走本端点，不可用时回落到 content。</p>
 *
 * <p><b>为什么不复用资料库的预览端点</b>：{@code /api/library/nodes/{id}/preview}
 * 的入参是资料库节点，取字节走 {@code LibraryBlobStore}；产物在会话磁盘目录，
 * 寻址参数（agentId / sessionId / 子会话白名单）与之完全不兼容。
 * 两者共用同一套预览服务与票据机制，但票据载荷与取数路径各自分流，
 * 见 {@link PreviewTicketService}。</p>
 *
 * <p><b>权限口径</b>：与产物面板其它端点一致——先过
 * {@link SessionAccessGuard#requireOwnedSessionRoot} 确认会话归属，
 * 再用 {@link SessionArtifactController#resolveArtifactFile} 解析文件
 * （内含子会话白名单与真实路径校验），<b>两者都通过之后才签票</b>：
 * 票据是给外部服务匿名回拉的窄门，不含用户身份语义，判权只能发生在这里。</p>
 */
@RestController
public class ArtifactPreviewController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactPreviewController.class);

    private final SessionAccessGuard accessGuard;
    private final SessionLineageService sessionLineageService;
    private final PreviewTicketService ticketService;
    private final DocumentPreviewClient client;
    private final DocumentPreviewProperties properties;

    public ArtifactPreviewController(SessionAccessGuard accessGuard,
                                     SessionLineageService sessionLineageService,
                                     PreviewTicketService ticketService,
                                     DocumentPreviewClient client,
                                     DocumentPreviewProperties properties) {
        this.accessGuard = accessGuard;
        this.sessionLineageService = sessionLineageService;
        this.ticketService = ticketService;
        this.client = client;
        this.properties = properties;
    }

    /**
     * 为产物文件生成预览地址（供前端 iframe 使用）。
     *
     * <p>顺序不可调换：<b>归属校验 → 文件解析 → 探活 → 签票</b>。
     * 探活放在解析之后，是为了让「文件不存在」这种更根本的错误先暴露，
     * 不被「预览服务未启用」掩盖。</p>
     *
     * @param agentId   智能体 id
     * @param sessionId 会话 id
     * @param path      产物树口径的相对路径（可能带子会话首段）
     * @param request   当前请求（用于取登录身份）
     * @return 可直接放进 iframe 的预览地址
     */
    @GetMapping("/api/agents/{agentId}/sessions/{sessionId}/artifacts/preview")
    public PreviewResponse preview(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestParam("path") String path,
            HttpServletRequest request) {
        if (!properties.enabled()) {
            throw ApiException.badRequest("文档预览功能未启用");
        }
        String userId = accessGuard.requireUserId(request);
        Path root = accessGuard.requireOwnedSessionRoot(agentId, userId, sessionId, request);
        Set<String> subSessions = new LinkedHashSet<>(
                sessionLineageService.subSessionsOf(userId, sessionId));
        Path target = SessionArtifactController.resolveArtifactFile(
                agentId.toString(), sessionId, root, subSessions, path);
        if (target == null) {
            throw ApiException.notFound("产物文件不存在: " + path);
        }

        // ⚠️ **此处不做任何格式判据**（含 CAD）：格式能否渲染由预览服务自己判定
        // ——它认得 dwg（FileUtils.typeFromFileName 里 return FileType.cad），
        // 认不得的类型也会渲染一个明确的「不支持该格式」页面。
        // 平台侧此前维护过三份名单（前端分流表 / 预览端点白名单 / 文本端点二进制黑名单）
        // 外加一份 CAD 特判，漏改一处就让某类型落到「这边以为能预览、那边按不支持拒掉」
        // 的夹缝，且**无任何东西报错**。判据交给唯一权威后，这一类故障直接消失。
        // 代价是不支持的格式要多一次往返才看到提示页——远小于名单与真实能力脱节。

        // 先探活：服务不可用时不必签票（票据单次消费，白签浪费）
        try {
            client.ensureAvailable();
        } catch (IllegalStateException e) {
            // 转成 400：这是部署/配置层面的可预期问题，不该以 500 呈现给前端
            throw ApiException.badRequest("文档预览服务不可用，请稍后重试或下载后查看");
        }

        // 真实文件名（含后缀）交给票据承载：它决定两件事——扩展名（供预览服务认格式）
        // 与回拉响应的 Content-Disposition。它本身不会进 URL，
        // 因为含中文/空格的名字会被 TipDM-FV 的两轮解码还原成裸字符、导致回拉 400。
        String fileName = target.getFileName().toString();
        String ticket = ticketService.issueArtifact(userId, agentId, sessionId, path, fileName);
        String sourceUrl = properties.publicBaseUrl() + "/api/doc-preview/raw?ticket=" + ticket;
        String previewUrl = client.buildPreviewUrl(sourceUrl, fileName);
        log.info("生成产物预览地址 agent={} session={} path={} file={}",
                agentId, sessionId, path, fileName);
        return new PreviewResponse(path, previewUrl);
    }

    /** 预览地址响应。 */
    public record PreviewResponse(String path, String previewUrl) {
    }
}
