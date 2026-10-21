package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.ArtifactZipService;
import com.tipdm.agentplatform.service.DownloadTicketService;
import com.tipdm.agentplatform.service.SessionLineageService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactContent;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactContentUpdate;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话产物（Artifacts）视图：树 / 读 / <b>覆盖写</b> / <b>下载</b>。
 *
 * <ul>
 *   <li>{@code GET /api/agents/{agentId}/sessions/{sessionId}/artifacts} — 产物文件树
 *   <li>{@code GET /api/agents/{agentId}/sessions/{sessionId}/artifacts/content?path=…} — 文件文本
 *   <li>{@code PUT /api/agents/{agentId}/sessions/{sessionId}/artifacts/content?path=…} — 覆盖写文件文本
 *   <li>{@code POST …/artifacts/download-ticket} — 换短时下载票据
 *   <li>{@code GET …/artifacts/download?path=…} — 下载（文件直出/ 目录打包 zip）
 * </ul>
 *
 * <p>产物 = 模型在一次会话中写入运行态目录的文件。目录解析复用
 * {@link SessionWorkspaceManager}，取数用 JDK NIO 递归（对齐 AgentScope paw 示例的
 * {@code AgentWorkspaceController#collectChildren}）——产物就是普通本地文件，
 * 不需要经过 AgentScope 的 {@code AbstractFilesystem} 抽象。</p>
 *
 * <p><strong>子会话聚合（2026-09-18 改版）：</strong>平台按会话隔离文件空间，子智能体的产物落在
 * <b>各自会话目录</b>（{@code runtimes/{agentId}/sub-…}，与父会话目录同级）。按「产出统一写入
 * {@code artifacts/} 目录」的平台契约（目录名取 {@code agent-platform.artifacts-dir} 配置），
 * 产物面板把<b>本会话与全部子会话的 {@code artifacts/}</b> 合并成一棵以 {@code artifacts} 为根的
 * 树：目录按名合并（同名目录 union），文件同名冲突时展示名追加会话短标（真实 {@code path}
 * 不变，内容回取仍走原会话）；artifacts 之外的会话内容（运行态内部目录、历史散落文件）一律
 * 不展示。子会话归属由 {@link SessionLineageService} 按框架记录的 spawn registry 判定——
 * 非本会话的子目录一律 404，避免越权读到其它会话。</p>
 *
 * <p><strong>为什么是会话级而不是 agent 级：</strong>AgentScope 官方的 workspace 视图
 * 指向 {@code agents/{agentId}/workspace}（定义区：AGENTS.md、skills/、subagents/），
 * 不带 sessionId；本平台的模型产物写在运行区 {@code runtimes/{agentId}/{sessionId}}，
 * 若照搬 agent 级接口，产物面板列出来的会是智能体定义文件而非用户会话产物。</p>
 *
 * <p><strong>归属校验：</strong>产物目录是两层、不含 userId，用户归属由
 * {@code agent_session_owner} 表承载。访问前按 {@code (agentId, sessionId)} 查出归属：
 * <ul>
 *   <li>归属用户 = 请求用户 → 放行；
 *   <li>归属用户 ≠ 请求用户 → 404（不暴露他人会话的存在性）；
 *   <li>无归属记录（历史会话 / 归属写入失败）→ admin 放行、普通用户 404。
 * </ul></p>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/artifacts")
public class SessionArtifactController {

    private static final Logger log = LoggerFactory.getLogger(SessionArtifactController.class);

    /** 递归深度上限，防止超深目录拖垮响应（与 AgentScope paw 示例一致）。 */
    private static final int MAX_DEPTH = 6;

    /**
     * 单文件预览上限。产物里常见 CSV / JSONL 日志，对齐 paw 示例放宽到 5MB，
     * 超限返回原因说明而不是请求失败。
     */
    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024;

    /**
     * 单次覆盖写的字节上限。比预览上限严得多：可编辑的是主编在前端手工改的结构化文本
     * （知识图谱 / 目录等 JSON，实测约 42KB），2MB 已远超合理范围，超出通常是调用方
     * 把整棵目录打包进来发错了。
     */
    private static final int MAX_WRITE_SIZE = 2 * 1024 * 1024;

    /** 判定为二进制、不做文本预览的扩展名（同 paw 前端 {@code BINARY_EXT}）。 */
    private static final Pattern BINARY_EXT = Pattern.compile(
            "\\.(png|jpe?g|gif|bmp|ico|webp|tiff?|heic|avif|pdf|docx?|xlsx?|pptx?|odt|ods|odp"
                    + "|zip|tar|t?gz|tbz2?|bz2|xz|7z|rar|jar|war|ear|class|exe|dll|so|dylib|a|o|bin|dat"
                    + "|sqlite3?|db|mdb|pyc|pyo|wasm|mp3|mp4|m4a|wav|flac|ogg|opus|aac|avi|mov|mkv|webm|wmv"
                    + "|ttf|otf|woff2?|eot)$",
            Pattern.CASE_INSENSITIVE);

    /** 二进制嗅探窗口：前 8KB 内出现 NUL 字节即视为二进制。 */
    private static final int SNIFF_BYTES = 8192;

    /**
     * 平台/框架的运行时内部目录与文件（产物面板不展示）。
     *
     * <p>这些是会话的**运行态内部资料**，不是模型给主编的产物：{@code agents/ default/ memory/}
     * 是框架的会话副本与记忆，{@code plan/} 是计划模式状态，{@code MEMORY.md} 同理；点开头的一律跳过。
     * 名字来自平台/框架本身，与任何智能体业务无关。</p>
     */
    private static final Set<String> RUNTIME_INTERNAL_NAMES = Set.of(
            "agents", "default", "memory", "plan", "MEMORY.md");

    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionLineageService sessionLineageService;
    private final SessionAccessGuard accessGuard;
    private final ArtifactZipService artifactZipService;
    private final DownloadTicketService downloadTicketService;

    /** 产物目录名（{@code agent-platform.artifacts-dir}），与会话工作空间的写入契约保持同源。 */
    @Value("${agent-platform.artifacts-dir:artifacts}")
    private String artifactsDirName;

    /**
     * 下载端点的异步超时（毫秒）。
     *
     * <p>按端点设定而非配 {@code spring.mvc.async.request-timeout} 全局值：
     * SSE 要 2 小时、下载要数分钟，全局一刀切会让两者互相破坏。</p>
     */
    private final long downloadTimeoutMillis;

    /** 打包专用线程池：避免占用 Tomcat 同步线程（打包同时吃 CPU 与磁盘 IO）。 */
    private final AsyncTaskExecutor artifactZipExecutor;

    public SessionArtifactController(SessionWorkspaceManager sessionWorkspaceManager,
                                     SessionLineageService sessionLineageService,
                                     SessionAccessGuard accessGuard,
                                     ArtifactZipService artifactZipService,
                                     DownloadTicketService downloadTicketService,
                                     @Qualifier("artifactZipExecutor")
                                     AsyncTaskExecutor artifactZipExecutor,
                                     @Value(
                                             "${agent-platform.artifacts-download.timeout-millis:600000}")
                                     long downloadTimeoutMillis) {
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.sessionLineageService = sessionLineageService;
        this.accessGuard = accessGuard;
        this.artifactZipService = artifactZipService;
        this.downloadTicketService = downloadTicketService;
        this.artifactZipExecutor = artifactZipExecutor;
        this.downloadTimeoutMillis = downloadTimeoutMillis;
    }

    // -----------------------------------------------------------------
    //  产物树
    // -----------------------------------------------------------------

    @GetMapping
    public List<ArtifactNode> tree(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        Path root = accessGuard.requireOwnedSessionRoot(agentId, userId, sessionId, request);

        String dirName = artifactsDirName;
        // 本会话的 artifacts/：path 前缀 = "artifacts/"（content 接口在本会话目录内解析）
        Path ownArtifacts = root.resolve(dirName);
        List<ArtifactNode> own = Files.isDirectory(ownArtifacts)
                ? collectChildren(ownArtifacts, dirName + "/", MAX_DEPTH)
                : List.of();
        // 子会话各贡献自己的 artifacts/：path 前缀 = "<subSessionId>/artifacts/"，
        // content 接口按首段子会话 ID 路由到子会话目录（白名单外不进合并）
        Map<String, List<ArtifactNode>> subs = new LinkedHashMap<>();
        for (String subSessionId : sessionLineageService.subSessionsOf(userId, sessionId)) {
            Path subArtifacts = sessionWorkspaceManager
                    .resolveSessionDir(agentId.toString(), subSessionId)
                    .resolve(dirName);
            if (Files.isDirectory(subArtifacts)) {
                subs.put(subSessionId, collectChildren(
                        subArtifacts, subSessionId + "/" + dirName + "/", MAX_DEPTH));
            }
        }
        return mergedArtifactRoots(dirName, own, subs);
    }

    /**
     * 以 artifacts 为根构建合并树：本会话与各子会话的 artifacts 内容按名合并（目录 union），
     * 文件同名冲突时子会话文件展示名追加会话短标——真实 {@code path} 始终是可回取的定位路径，
     * 合并只发生在展示层。全部为空时返回空表（前端显示"暂无产物"）。
     *
     * <p>静态以便单测：{@code subs} 的键必须已经过血缘白名单过滤（调用方职责）。</p>
     *
     * @param artifactsDirName 根节点展示名（取配置，通常 {@code artifacts}）
     * @param own              本会话 artifacts/ 的收集结果（path 已带 {@code artifacts/} 前缀）
     * @param subs             子会话 ID → 该子会话 artifacts/ 的收集结果（path 已带会话前缀）
     */
    public static List<ArtifactNode> mergedArtifactRoots(String artifactsDirName,
                                                  List<ArtifactNode> own,
                                                  Map<String, List<ArtifactNode>> subs) {
        List<ArtifactNode> merged = new ArrayList<>();
        mergeInto(merged, own, null);
        for (Map.Entry<String, List<ArtifactNode>> entry : subs.entrySet()) {
            mergeInto(merged, entry.getValue(), shortTag(entry.getKey()));
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        return List.of(new ArtifactNode(
                artifactsDirName, artifactsDirName, "dir", null, null, merged));
    }

    /**
     * 把一组节点并入展示树：同名目录递归 union；同名文件并存、后来者展示名加会话短标
     * （{@code 目录.md · 1a2b3c4d}）；同名异型（一边目录一边文件）不去重、共存。
     * 合并后按「目录优先、名称升序」重排。
     */
    private static void mergeInto(List<ArtifactNode> base, List<ArtifactNode> incoming,
                                  String ownerTag) {
        for (ArtifactNode node : incoming) {
            int idx = -1;
            for (int i = 0; i < base.size(); i++) {
                ArtifactNode n = base.get(i);
                if (n.name().equals(node.name()) && n.type().equals(node.type())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                base.add(node);
                continue;
            }
            ArtifactNode existing = base.get(idx);
            if ("dir".equals(node.type())) {
                // 同名目录 union：子节点递归合并；目录 path 保留首个贡献者的（目录仅用于展开，不回取）
                List<ArtifactNode> children = new ArrayList<>(existing.children() == null
                        ? List.of() : existing.children());
                mergeInto(children, node.children() == null ? List.of() : node.children(), ownerTag);
                base.set(idx, new ArtifactNode(existing.name(), existing.path(), "dir",
                        null, later(existing.updatedAt(), node.updatedAt()), children));
            } else if ("file".equals(node.type()) && ownerTag != null) {
                String tagged = node.name() + " · " + ownerTag;
                int suffix = 2;
                while (nameExists(base, tagged)) {
                    tagged = node.name() + " · " + ownerTag + " " + suffix++;
                }
                base.add(new ArtifactNode(tagged, node.path(), "file",
                        node.size(), node.updatedAt(), null));
            } else {
                base.add(node);
            }
        }
        base.sort(Comparator
                .<ArtifactNode, Integer>comparing(n -> "dir".equals(n.type()) ? 0 : 1)
                .thenComparing(ArtifactNode::name));
    }

    /** 子会话短标：去掉 {@code sub-} 前缀后取前 8 位（{@code sub-1a2b3c4d-…} → {@code 1a2b3c4d}）。 */
    private static String shortTag(String subSessionId) {
        String s = subSessionId.startsWith("sub-") ? subSessionId.substring(4) : subSessionId;
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static boolean nameExists(List<ArtifactNode> nodes, String name) {
        return nodes.stream().anyMatch(n -> n.name().equals(name));
    }

    private static Long later(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    // -----------------------------------------------------------------
    //  文件内容
    // -----------------------------------------------------------------

    @GetMapping("/content")
    public ArtifactContent content(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestParam("path") String path,
            HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        String rel = validateRelPath(path);

        if (BINARY_EXT.matcher(rel).find()) {
            return new ArtifactContent(rel, null, "二进制文件，暂不支持预览");
        }

        Path root = accessGuard.requireOwnedSessionRoot(agentId, userId, sessionId, request);
        Set<String> subSessions = new LinkedHashSet<>(
                sessionLineageService.subSessionsOf(userId, sessionId));
        Path target = resolveArtifactFile(agentId.toString(), sessionId, root, subSessions, rel);
        if (target == null) {
            throw ApiException.notFound("产物文件不存在: " + rel);
        }
        return readAsText(target, rel);
    }

    // -----------------------------------------------------------------
    //  覆盖写
    // -----------------------------------------------------------------

    /**
     * 覆盖写产物文件文本（主编在人工闸门里修改模型产物后落盘）。
     *
     * <p>请求体 {@code content} 是完整文件内容；返回更新后的文件节点（size / updatedAt 已刷新），
     * 前端可据此直接更新产物树，无需重拉整棵树。</p>
     *
     * @throws ApiException 401 未登录 / 404 会话或文件不存在（含越权，不区分）/ 400 路径非法、
     *                      二进制、内容为空或超限 / 500 落盘失败
     */
    @PutMapping("/content")
    public ArtifactNode saveContent(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestParam("path") String path,
            @RequestBody(required = false) ArtifactContentUpdate body,
            HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        String rel = validateRelPath(path);

        if (BINARY_EXT.matcher(rel).find()) {
            throw ApiException.badRequest("二进制产物不支持编辑: " + rel);
        }
        String content = body == null ? null : body.content();
        if (content == null) {
            throw ApiException.badRequest("content 不能为空");
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_WRITE_SIZE) {
            throw ApiException.badRequest("内容过大（" + bytes + " 字节），超过 "
                    + (MAX_WRITE_SIZE / 1024 / 1024) + "MB 覆盖写上限");
        }

        Path root = accessGuard.requireOwnedSessionRoot(agentId, userId, sessionId, request);
        Set<String> subSessions = new LinkedHashSet<>(
                sessionLineageService.subSessionsOf(userId, sessionId));
        // 复用读接口的解析：子会话白名单 + normalize + 真实路径二次校验，一处收口越权防护
        Path target = resolveArtifactFile(agentId.toString(), sessionId, root, subSessions, rel);
        if (target == null) {
            // 只覆盖已存在的产物文件——不新建、不删除，产物目录的增删属于模型职责
            throw ApiException.notFound("产物文件不存在: " + rel);
        }

        try {
            writeAtomically(target, content);
        } catch (IOException e) {
            log.warn("写入产物失败 {}: {}", target, e.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ARTIFACT_WRITE_FAILED", "产物写入失败: " + e.getMessage());
        }
        log.info("覆盖写产物 {}/{} → {} ({} 字节)", agentId, sessionId, rel, bytes);
        return new ArtifactNode(target.getFileName().toString(), rel, "file",
                sizeOf(target), lastModified(target), null);
    }

    // -----------------------------------------------------------------
    //  下载（文件 / 目录打包）
    // -----------------------------------------------------------------

    /**
     * 换一张短时下载票据，供浏览器原生下载使用。
     *
     * <p>浏览器原生下载（{@code <a href download>}）无法附加 {@code Authorization} 头，
     * 而把 access token 写进 URL 会进浏览器历史、Referer 与访问日志。此端点用**正常的
     * 带请求头请求**换一张权限更窄的票据（单次、60 秒、绑定具体路径），
     * 前端拿到后交给 {@code <a href=".../download?ticket=...">}，
     * 下载全程不暴露 access token。</p>
     *
     * <p>换票时即完成归属校验与路径存在性检查：不合法的请求在这里就被拒，
     * 不会浪费一张 60 秒有效的票据。</p>
     */
    @PostMapping("/download-ticket")
    public DownloadTicket issueTicket(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestParam(value = "path", required = false) String path,
            HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        String rel = path == null || path.isBlank() ? "" : validateRelPath(path);
        Path root = accessGuard.requireOwnedSessionRoot(agentId, userId, sessionId, request);
        Set<String> subSessions = subSessionWhitelist(userId, sessionId);

        String fileName;
        if (!rel.isEmpty()) {
            // 单文件：确认可解析；目录：确认存在——两者都不是就 404，不签票。
            // zip 名取路径末段：目录只做 union 合并、不改名（加会话短标的是同名文件），
            // 因此定位 path 的末段与合并树里的展示名一致。
            if (resolveArtifactFile(agentId.toString(), sessionId, root, subSessions, rel) != null) {
                fileName = lastSegment(rel);
            } else if (resolveArtifactDir(agentId.toString(), sessionId, root, subSessions, rel)
                    != null) {
                fileName = zipName(lastSegment(rel));
            } else {
                throw ApiException.notFound("产物不存在: " + rel);
            }
        } else {
            fileName = artifactsDirName + ".zip";
        }
        String ticket = downloadTicketService.issue(userId, agentId, sessionId, rel, fileName);
        return new DownloadTicket(ticket, fileName, 60);
    }

    /**
     * 下载产物：目标解析为文件则直出字节流，解析为目录则打包成 zip。
     *
     * <p><strong>双入参</strong>：带 {@code Authorization} 头时按普通接口鉴权（服务间调用、
     * 前端 File System Access API 直写磁盘）；无头但带 {@code ?ticket=} 时由票据自证身份——
     * 后者之所以能进到这里，是因为 {@code JwtAuthFilter} 精确放行了「带 ticket 的下载请求」，
     * 其余产物请求仍受过滤器保护。</p>
     *
     * <p>目录打包复用产物面板的合并树（{@link #mergedArtifactRoots}），
     * 因此解压得到的文件名与用户在面板上看到的逐字一致。</p>
     *
     * @param path 可选；缺省表示合并树全量
     */
    @GetMapping("/download")
    public WebAsyncTask<ResponseEntity<StreamingResponseBody>> download(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "ticket", required = false) String ticketParam,
            HttpServletRequest request) {

        String rel = path == null || path.isBlank() ? "" : validateRelPath(path);
        boolean viaTicket = ticketParam != null && !ticketParam.isBlank();
        DownloadTicketService.Ticket ticket = null;
        String userId;

        if (viaTicket) {
            // 票据自带身份：先用它核销（真单次），再用它做归属校验
            ticket = downloadTicketService.consume(ticketParam, agentId, sessionId)
                    .orElseThrow(() -> ApiException.unauthorized("下载票据无效或已过期，请重试"));
            userId = ticket.userId();
            // path 以票据内绑定的为准，不采信 URL 参数——否则票据绑定形同虚设
            rel = ticket.path();
        } else {
            userId = accessGuard.requireUserId(request);
        }

        Path root = accessGuard.requireOwnedSessionRoot(agentId, userId, sessionId, request);
        Set<String> subSessions = subSessionWhitelist(userId, sessionId);
        DownloadTarget target = resolveDownloadTarget(
                agentId.toString(), sessionId, root, subSessions, rel);

        // 预检必须在写响应头之前：一旦流式响应开始，超限就只能以
        // 「下载到一半坏掉」的形式暴露，无法再改成 413
        if (target.zip()) {
            List<ArtifactNode> roots = buildMergeRoots(agentId, userId, sessionId, root, subSessions);
            DownloadTarget.Resolved resolved = target.resolve(roots);
            if (resolved.roots().isEmpty()) {
                throw ApiException.notFound("产物为空: " + displayPath(rel));
            }
            artifactZipService.preflight(resolved.roots(), resolved.displayBase(), root, subSessions);
        }

        String fileName = viaTicket && ticket != null && !ticket.fileName().isBlank()
                ? ticket.fileName()
                : target.fileName();
        String disposition = "attachment; filename*=UTF-8''" + urlEncode(fileName);

        StreamingResponseBody body = out -> {
            if (!target.zip()) {
                Files.copy(target.file(), out);
                return;
            }
            // 合并树在此处重算：预检与开流之间产物可能变化，重算能避免用过期快照
            Set<String> subs = subSessionWhitelist(userId, sessionId);
            List<ArtifactNode> roots = buildMergeRoots(agentId, userId, sessionId, root, subs);
            DownloadTarget.Resolved resolved = target.resolve(roots);
            artifactZipService.pack(resolved.roots(), resolved.displayBase(), root, subs, out);
        };

        ResponseEntity<StreamingResponseBody> response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(target.zip()
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.APPLICATION_OCTET_STREAM)
                .body(body);

        // 专用线程池 + 端点级超时：不占Tomcat 同步线程，也不与 SSE 的 2h 冲突。
        // 注意：WebAsyncTask 的执行器参数类型是 AsyncTaskExecutor（不是 core.task.TaskExecutor），
        // 且必须与 Callable 一起传三参构造——不存在 (timeout, executor) 两参重载。
        return new WebAsyncTask<ResponseEntity<StreamingResponseBody>>(
                downloadTimeoutMillis, artifactZipExecutor, () -> response);
    }

    /** 换票响应体。 */
    public record DownloadTicket(String ticket, String fileName, long expiresInSeconds) { }

    /** 下载目标：单文件直出，或目录打包。
     *
     * @param zip         是否打包成 zip
     * @param file        单文件时的磁盘路径（zip 时为 null）
     * @param fileName建议的下载文件名
     * @param subtreePath 要打包的子树在合并树中的定位路径；null 表示合并树全量。
     *                   不能对目录直接按磁盘路径扫盘——子会话的同名目录内容不在那个目录里，
     *                   必须回到合并树按展示名定位，否则子目录下载会打成全量产物。
     */
    public record DownloadTarget(boolean zip, Path file, String fileName, String subtreePath) {

        /**
         * 在合并树里解析出要打包的条目根与条目名前缀。
         *
         * <p><strong>条目名前缀取目录的展示名（{@code node.name()}），不能取定位path。</strong>
         * 定位 path 可能带 {@code <subSessionId>/artifacts/} 前缀——那是回磁盘用的内部坐标，
         * 泄漏到解压结构里就是 {@code sub-299b92cf/artifacts/…/} 这种多出两层的目录，
         * 与面板上看到的树不一致。zip 名是 {@code _temp.zip}、解压出来却要再点三层
         * 才能看到内容，违反直觉。</p>
         *
         * <p>用展示名做前缀后，解压出来的顶层就是用户点开的那个目录本身，
         * 与 zip 文件名互相印证；全量下载的前缀是空串、根节点自带 {@code artifacts} 名，
         * 两者是同一条规则。</p>
         *
         * <p>目录节点 path 只指首个贡献者，但其 {@code children} 是父会话与全部子会话
         * 合并后的内容——这正是必须回到合并树取子树的原因。定位不到时返回空列表，
         * 由调用方转 404（不退回全量）。</p>
         */
        public Resolved resolve(List<ArtifactNode> mergedRoots) {
            if (subtreePath == null) {
                return new Resolved("", mergedRoots);
            }
            ArtifactNode dir = findByPath(mergedRoots, subtreePath);
            if (dir == null || dir.children() == null) {
                return new Resolved("", List.of());
            }
            return new Resolved(dir.name(), dir.children());
        }

        /** 条目名前缀 + 待打包的条目根。 */
        public record Resolved(String displayBase, List<ArtifactNode> roots) { }

        /** 在合并树中按 path 深度优先查找（path 是可回取的定位路径，合并后仍保持唯一）。 */
        private static ArtifactNode findByPath(List<ArtifactNode> nodes, String path) {
            for (ArtifactNode node : nodes) {
                if (path.equals(node.path())) {
                    return node;
                }
                if (node.children() != null && !node.children().isEmpty()) {
                    ArtifactNode hit = findByPath(node.children(), path);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            return null;
        }
    }

    /**
     * 把请求的相对路径解析成下载目标。缺省路径 = 合并树全量。
     *
     * <p>先试文件、再试目录：两者都不中即 404。顺序不可颠倒——同名文件与同名目录
     * 可以共存（合并逻辑对异型不去重），此时按「文件优先」与产物面板的点击语义一致。</p>
     */
    private DownloadTarget resolveDownloadTarget(String agentId, String sessionId, Path root,
                                                  Set<String> subSessions, String rel) {
        if (rel.isEmpty()) {
            // 全量：条目名直接沿用合并树展示路径（前缀空串，根节点自带 artifacts 名）
            return new DownloadTarget(true, null, artifactsDirName + ".zip", null);
        }
        Path file = resolveArtifactFile(agentId, sessionId, root, subSessions, rel);
        if (file != null) {
            return new DownloadTarget(false, file, lastSegment(rel), null);
        }
        Path dir = resolveArtifactDir(agentId, sessionId, root, subSessions, rel);
        if (dir != null) {
            // 子目录打包：zip 名取该目录的展示名。条目名前缀由 DownloadTarget.resolve()
            // 从合并树里取 node.name()，不在这里定——定位 path 可能带子会话前缀。
            return new DownloadTarget(true, null, zipName(lastSegment(rel)), rel);
        }
        throw ApiException.notFound("产物不存在: " + rel);
    }

    /** 子会话白名单：产物面板与下载共用，避免两处口径漂移。 */
    private Set<String> subSessionWhitelist(String userId, String sessionId) {
        return new LinkedHashSet<>(sessionLineageService.subSessionsOf(userId, sessionId));
    }

    /** 重建合并树（与 {@link #tree} 同源，供打包使用）。 */
    private List<ArtifactNode> buildMergeRoots(UUID agentId, String userId, String sessionId,
                                               Path root, Set<String> subSessions) {
        Path ownArtifacts = root.resolve(artifactsDirName);
        List<ArtifactNode> own = Files.isDirectory(ownArtifacts)
                ? collectChildren(ownArtifacts, artifactsDirName + "/", MAX_DEPTH)
                : List.of();
        Map<String, List<ArtifactNode>> subs = new LinkedHashMap<>();
        for (String subSessionId : subSessions) {
            Path subArtifacts = sessionWorkspaceManager
                    .resolveSessionDir(agentId.toString(), subSessionId)
                    .resolve(artifactsDirName);
            if (Files.isDirectory(subArtifacts)) {
                subs.put(subSessionId, collectChildren(
                        subArtifacts, subSessionId + "/" + artifactsDirName + "/", MAX_DEPTH));
            }
        }
        return mergedArtifactRoots(artifactsDirName, own, subs);
    }

    /** 取路径最后一段作为文件名。 */
    private static String lastSegment(String rel) {
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? rel : rel.substring(idx + 1);
    }

    private static String zipName(String name) {
        return name.endsWith(".zip") ? name : name + ".zip";
    }

    /** RFC 5987 文件名编码（中文名安全），与 LibraryController 的下载端点同口径。 */
    private static String urlEncode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 仅用于错误信息的路径展示。 */
    private static String displayPath(String rel) {
        return rel.isEmpty() ? "(全部)" : rel;
    }

    // -----------------------------------------------------------------
    //  内部实现
    // -----------------------------------------------------------------

    /**
     * 判断 {@code rel} 是否落在产物目录（{@code artifactsDirName}/）之内——本会话或血缘白名单内的子会话。
     *
     * <p>与 {@link #resolveArtifactFile} 同一套路径口径：相对路径以会话目录为起点，首段若是子会话 ID
     * 则跳过该段再看剩余部分。因此 {@code artifacts/x.md} 与 {@code <subSessionId>/artifacts/x.md}
     * 都算产物之内；运行态内部目录（{@code memory/} 等）与 artifacts 之外的历史散落文件不算。</p>
     *
     * <p>静态以便单测：{@code subSessions} 必须已经过血缘白名单过滤（调用方职责）。</p>
     */
    static boolean isWritableArtifact(String artifactsDirName, Set<String> subSessions, String rel) {
        String dir = artifactsDirName == null || artifactsDirName.isBlank()
                ? "artifacts" : artifactsDirName;
        String rest = rel == null ? "" : rel;
        int slash = rest.indexOf('/');
        if (slash > 0 && subSessions != null && subSessions.contains(rest.substring(0, slash))) {
            rest = rest.substring(slash + 1);
        }
        return rest.startsWith(dir + "/");
    }

    /**
     * 解析产物文件：{@code rel} 首段若是**本会话的子会话 ID**，就读该子会话目录；否则读本会话目录。
     *
     * <p>越权防护：子会话白名单来自 {@link SessionLineageService}（只含本会话的子会话），
     * 非白名单的首段一律当普通相对路径在**本会话目录**内解析——不存在的自然 404，
     * 不会读到兄弟会话的文件。</p>
     *
     * @return 命中且为普通文件时返回绝对路径；否则 {@code null}
     */
    public static Path resolveArtifactFile(String agentId, String sessionId, Path sessionRoot,
                                           Set<String> subSessions, String rel) {
        String prefix = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        Path base = sessionRoot;
        String rest = rel;
        if (subSessions.contains(prefix)) {
            rest = rel.substring(prefix.length());
            while (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            base = sessionRoot.getParent() == null ? null : sessionRoot.getParent().resolve(prefix);
            if (base == null) {
                return null;
            }
        }
        Path target = rest.isEmpty() ? base : base.resolve(rest).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) {
            return null;
        }
        // 符号链接穿透防护：startsWith 只比对字面路径，不解析链接。会话目录是模型可写的
        // （execute 工具能创建 symlink），一个指向 /etc/passwd 之类的软链会让预览越出
        // 会话目录读到宿主任意文件。对真实路径（toRealPath 解析全部链接）再校验一次起点。
        try {
            Path realBase = base.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realBase)) {
                log.warn("产物预览被拒：{} 的真实路径 {} 越出会话目录 {}", rel, realTarget, realBase);
                return null;
            }
            target = realTarget;
        } catch (IOException e) {
            // 目录/文件在解析期间消失（会话被清理）等：按不存在处理
            return null;
        }
        return target;
    }

    /**
     * 解析产物**目录**：语义与 {@link #resolveArtifactFile} 平行，区别只在于要求目标是目录。
     *
     * <p>不能直接复用 {@code resolveArtifactFile}——它末尾要求 {@code isRegularFile}，
     * 传目录会得到 null。这里照搬它的三道防护（子会话白名单首段路由、normalize + startsWith、
     * 真实路径二次校验），保证目录下载与文件读取的越权口径完全一致。</p>
     *
     * <p><strong>目录场景下符号链接防护更关键</strong>：文件读取至多泄露单个文件内容，
     * 而目录会被递归遍历——模型用 execute 建一个指向 {@code /etc} 的软链，
     * 递归就会把宿主目录整个打进包。</p>
     *
     * @return 命中且为目录时返回真实绝对路径；否则 {@code null}
     */
    public static Path resolveArtifactDir(String agentId, String sessionId, Path sessionRoot,
                                      Set<String> subSessions, String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        String prefix = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        Path base = sessionRoot;
        String rest = rel;
        if (subSessions.contains(prefix)) {
            rest = rel.substring(prefix.length());
            while (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            base = sessionRoot.getParent() == null ? null : sessionRoot.getParent().resolve(prefix);
            if (base == null) {
                return null;
            }
        }
        Path target = base.resolve(rest).normalize();
        if (!target.startsWith(base)) {
            return null;
        }
        try {
            // 真实路径校验：解析全部符号链接后再判起点，防止软链把递归遍历带出会话目录
            Path realBase = base.toRealPath();
            Path realTarget = target.toRealPath();
            if (!Files.isDirectory(realTarget) || !realTarget.startsWith(realBase)) {
                log.warn("产物目录下载被拒：{} 的真实路径 {} 越出会话目录或不是目录", rel, realTarget);
                return null;
            }
            return realTarget;
        } catch (IOException e) {
            // 目录在解析期间消失（会话被清理）等：按不存在处理
            return null;
        }
    }

    /**
     * 递归收集目录树：隐藏文件（dotfile）与运行态内部目录跳过，目录优先排序，不可读目录静默忽略。
     *
     * @param prefix 展示用路径前缀（本会话为 {@code ""}，子会话为 {@code "<subSessionId>/"}）
     */
    public static List<ArtifactNode> collectChildren(Path dir, String prefix, int depth) {
        List<ArtifactNode> out = new ArrayList<>();
        if (depth <= 0 || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path entry : ds) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || RUNTIME_INTERNAL_NAMES.contains(name)) {
                    continue;
                }
                String rel = prefix + name;
                if (Files.isDirectory(entry)) {
                    out.add(new ArtifactNode(
                            name, rel, "dir", null, lastModified(entry),
                            collectChildren(entry, rel + "/", depth - 1)));
                } else {
                    out.add(new ArtifactNode(name, rel, "file", sizeOf(entry), lastModified(entry), null));
                }
            }
        } catch (IOException e) {
            log.warn("跳过不可读目录 {}: {}", dir, e.getMessage());
        }
        out.sort(Comparator
                .<ArtifactNode, Integer>comparing(n -> "dir".equals(n.type()) ? 0 : 1)
                .thenComparing(ArtifactNode::name));
        return out;
    }

    /**
     * 读取产物文件为文本：超限、二进制、非 UTF-8 均降级返回 {@code reason}，不抛异常。
     */
    private ArtifactContent readAsText(Path target, String rel) {
        long size = sizeOf(target);
        if (size > MAX_FILE_SIZE) {
            return new ArtifactContent(
                    rel, null, "文件过大（" + size + " 字节），超过 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB 预览上限");
        }
        if (looksBinary(target)) {
            return new ArtifactContent(rel, null, "二进制文件，暂不支持预览");
        }
        try {
            return new ArtifactContent(rel, Files.readString(target, StandardCharsets.UTF_8), null);
        } catch (IOException e) {
            // MalformedInputException（非 UTF-8 文本）也是 IOException，统一在此收敛
            log.warn("读取产物失败 {}: {}", target, e.getMessage());
            return new ArtifactContent(rel, null, "文件读取失败或不是 UTF-8 文本");
        }
    }

    /** 前 {@value #SNIFF_BYTES} 字节内出现 NUL 即判定为二进制。 */
    private static boolean looksBinary(Path file) {
        byte[] buf = new byte[SNIFF_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(buf, 0, buf.length);
            for (int i = 0; i < read; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            // 读不动就交给上层 readString 给出统一错误
            return false;
        }
        return false;
    }

    /**
     * 原子覆盖写文本：先写同目录临时文件、再替换目标，避免并发读方（模型 / 产物预览）
     * 读到写了一半的半截 JSON。
     *
     * <p>临时文件以 {@code .} 开头——产物树本就跳过 dotfile，写入中途不会被列出来。
     * 少数文件系统（部分网络盘 / 容器 bind mount）不支持 {@code ATOMIC_MOVE}，
     * 此时退化为普通替换：仍保证「要么旧内容、要么新内容」，只是替换瞬间有极小窗口。</p>
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, ".art-write-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 校验调用方传入的会话内相对路径：拒绝空、绝对路径、以及含 {@code .} / {@code ..} 的段。
     *
     * <p>包级可见以便同包的产物导出接口复用同一套校验口径。</p>
     */
    static String validateRelPath(String path) {
        if (path == null || path.isBlank()) {
            throw ApiException.badRequest("path 不能为空");
        }
        String trimmed = path.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest("非法路径");
        }
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw ApiException.badRequest("非法路径: " + path);
            }
        }
        return trimmed;
    }

    private static Long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static Long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return null;
        }
    }
}
