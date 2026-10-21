package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.SkillRepositoryProperties;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.SkillDtos.SkillRequest;
import com.tipdm.agentplatform.web.dto.SkillDtos.SkillResponse;
import com.tipdm.agentplatform.web.dto.SkillRepositoryDtos.SkillRepoCard;
import com.tipdm.agentplatform.web.dto.SkillRepositoryDtos.SkillRepoInfo;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 外部技能仓库接入（配置见 {@link SkillRepositoryProperties}）。
 *
 * <p>三种仓库类型共用一套「条目缓存」：git 类型同步 = shallow clone / fetch+reset
 * 后扫描缓存目录内全部 {@code SKILL.md}；http 类型同步 = 拉取 JSON 索引；
 * clawhub 类型同步 = 调 ClawHub（clawhub.ai）公开浏览 API 抓目录页。
 * 列表/详情访问按 cache-ttl 懒刷新；正文（markdown）git 型随扫描就绪，
 * http 型在详情/导入时按条目 url 懒加载，clawhub 型在详情/导入时调
 * {@code /api/v1/skills/{slug}/file?path=SKILL.md} 懒加载。</p>
 *
 * <p>条目 id：git 型取 SKILL.md 所在目录的相对路径（内部把 {@code /} 替换为
 * {@code --}，保证无斜杠可安全拼 URL 路径）；http 型取索引条目的 name；
 * clawhub 型对齐官方 ClawSkillHub 的 {@code owner/slug} 卡片 id，同样以
 * {@code --} 替换 {@code /} 存入缓存索引（回查 owner/slug 用 side map，不做
 * 反向字符串拆分）。</p>
 *
 * <p>clawhub 型参照官方 {@code agentscope.app.hub._skill._claw_hub.ClawSkillHub}
 * （AgentScope 2.0）：浏览 {@code GET /api/v1/skills}（cursor 分页）、
 * {@code GET /api/v1/skills/{slug}?ownerHandle=} 详情、
 * {@code GET /api/v1/skills/{slug}/file?path=SKILL.md} 正文；
 * Bearer token（clh_...）可选，仅影响限流额度；429 按 Retry-After 等响应头
 * 抖动重试。</p>
 */
@Service
public class SkillRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(SkillRepositoryService.class);

    private static final String SKILL_MD = "SKILL.md";
    /** 条目 id 中替代路径分隔符的标记（见类注释）。 */
    private static final String ID_SEP = "--";

    private static final String TYPE_HTTP = "http";
    private static final String TYPE_CLAWHUB = "clawhub";
    private static final String TYPE_SKILLHUB = "skillhub";
    /** ClawHub 官方站点；配置未给 url 时使用。 */
    private static final String DEFAULT_CLAWHUB_URL = "https://clawhub.ai";
    /** SkillHub API 域名（主站 skillhub.cn 只是 SPA，业务 API 在 api 子域）。 */
    private static final String DEFAULT_SKILLHUB_API = "https://api.skillhub.cn";
    /** SkillHub 主站，用于拼技能详情页 url（前端展示用）。 */
    private static final String SKILLHUB_SITE = "https://skillhub.cn";
    /** ClawHub 目录抓取页数上限（每页 200），限定单次同步规模。 */
    private static final int CLAWHUB_MAX_BROWSE = 1000;
    /** SkillHub 目录抓取条数上限（每页 200），平台量级数万，限单次同步规模。 */
    private static final int SKILLHUB_MAX_BROWSE = 2000;
    /** SkillHub 目录分页大小。 */
    private static final int SKILLHUB_PAGE_SIZE = 200;
    /** ClawHub 限流（429）最大重试次数，对齐官方默认。 */
    private static final int CLAWHUB_MAX_RETRIES = 3;

    private final SkillRepositoryProperties props;
    private final ObjectMapper objectMapper;
    private final SkillService skillService;

    /** 已同步的技能条目；markdown 对 http/clawhub/skillhub 型可能为 null（懒加载后回填）。 */
    private volatile List<SkillRepoCard> cache = List.of();

    /** 保护 {@link #cache} 的读改写（懒加载回填），避免并发请求互相覆盖。 */
    private final Object cacheLock = new Object();
    /**
     * 远端目录型仓库（clawhub / skillhub）专用：条目 id → {namespaceHandle（可空）,
     * slug}，详情/导入时回查定位，不做反向字符串拆分。
     */
    private volatile Map<String, String[]> remoteRefs = Map.of();
    private volatile Instant lastSyncedAt;
    private volatile String lastError;

    /** 后台刷新线程：TTL 过期后先吐旧数据再异步重新同步，接口不阻塞。 */
    private final ExecutorService refreshExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "skill-repo-refresh");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean refreshing =
            new AtomicBoolean(false);

    public SkillRepositoryService(SkillRepositoryProperties props,
                                  ObjectMapper objectMapper,
                                  SkillService skillService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.skillService = skillService;
    }

    /** 启动时从磁盘恢复上次同步的目录缓存，重启后无需重抓远端。 */
    @PostConstruct
    void loadCatalogFromDisk() {
        if (!props.isEnabled()) {
            return;
        }
        Path file = catalogFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            applyCatalogJson(Files.readString(file, StandardCharsets.UTF_8));
            log.info("技能仓库目录缓存已从磁盘恢复，共 {} 个技能（{}）", cache.size(), file);
        } catch (Exception e) {
            log.warn("技能仓库目录缓存恢复失败，忽略磁盘缓存: {} ({})", file, e.getMessage());
        }
    }

    @PreDestroy
    void shutdownRefresh() {
        refreshExecutor.shutdownNow();
    }

    // ── 对外查询 ─────────────────────────────────────────────────────────────

    public SkillRepoInfo info() {
        refreshIfNeeded(false);
        return toInfo();
    }

    public SkillRepoInfo sync() {
        refresh(true);
        return toInfo();
    }

    public PageResponse<SkillRepoCard> list(String search, int page, int size) {
        refreshIfNeeded(false);
        List<SkillRepoCard> filtered = filter(cache, search);
        int pageSize = Math.min(Math.max(size, 1), 200);
        int from = Math.max(page, 0) * pageSize;
        List<SkillRepoCard> content = from >= filtered.size()
                ? List.of() : filtered.subList(from, Math.min(from + pageSize, filtered.size()));
        int totalPages = (int) Math.ceil(filtered.size() / (double) pageSize);
        return new PageResponse<>(List.copyOf(content), Math.max(page, 0), pageSize,
                filtered.size(), totalPages);
    }

    public SkillRepoCard detail(String id) {
        refreshIfNeeded(false);
        SkillRepoCard card = findById(id);
        if (card == null) {
            throw ApiException.notFound("技能仓库中不存在该技能: " + id);
        }
        if (card.markdown() == null) {
            String type = props.getType();
            SkillRepoCard enriched;
            if (TYPE_CLAWHUB.equalsIgnoreCase(type)) {
                enriched = fetchClawhubMarkdown(card);
            } else if (TYPE_SKILLHUB.equalsIgnoreCase(type)) {
                enriched = fetchSkillhubMarkdown(card);
            } else {
                enriched = fetchHttpMarkdown(card);
            }
            // 懒加载回填是与并发请求共享的可变状态：原实现直接 `cache = cache.stream()...`
            // 是对 volatile 字段的非原子读改写，两个技能同时点开详情时会丢一次回填。
            synchronized (cacheLock) {
                cache = cache.stream().map(c -> c.id().equals(id) ? enriched : c).toList();
            }
            return enriched;
        }
        return card;
    }

    /** http 型懒加载正文：按索引条目声明的 url 拉取 SKILL.md，frontmatter 字段兜底。 */
    private SkillRepoCard fetchHttpMarkdown(SkillRepoCard card) {
        if (!StringUtils.hasText(card.url())) {
            throw ApiException.badRequest("该技能未提供 SKILL.md 下载地址，无法获取正文: " + card.name());
        }
        ParsedSkillMd parsed = parseSkillMarkdown(httpGet(card.url(), "text/markdown"), card.name());
        return new SkillRepoCard(card.id(),
                firstNonBlank(parsed.name(), card.name()),
                firstNonBlank(parsed.description(), card.description()),
                firstNonBlank(parsed.version(), card.version()),
                firstNonBlank(parsed.author(), card.author()),
                !parsed.tags().isEmpty() ? parsed.tags() : card.tags(),
                card.updatedAt(), card.url(), parsed.body());
    }

    /**
     * clawhub 型懒加载正文：调 {@code GET /api/v1/skills/{slug}?ownerHandle=} 与
     * {@code GET /api/v1/skills/{slug}/file?path=SKILL.md}（对齐官方 get_skill 的
     * 双请求），frontmatter 的 description 兜底。
     */
    private SkillRepoCard fetchClawhubMarkdown(SkillRepoCard card) {
        String[] ref = remoteRefs.get(card.id());
        if (ref == null) {
            throw ApiException.notFound("技能仓库缓存中没有该技能的定位信息: " + card.id());
        }
        String slug = ref[1];
        String base = clawhubBaseUrl();
        String skillsPath = "/api/v1/skills/" + slug;
        JsonNode detail;
        try {
            detail = objectMapper.readTree(httpGet(base + skillsPath, "application/json"));
        } catch (IOException e) {
            throw new IllegalStateException("ClawHub 详情响应不是合法 JSON: " + e.getMessage());
        }
        // detail 载荷：{skill:{slug,...}, latestVersion:{version}|..., owner:{...}}
        JsonNode item = detail.path("skill").isObject() ? detail.path("skill")
                : objectMapper.createObjectNode();
        JsonNode owner = detail.path("owner");
        ParsedSkillMd parsed = parseSkillMarkdown(
                httpGet(base + skillsPath + "/file?path=SKILL.md", "text/markdown"), slug);
        return new SkillRepoCard(card.id(),
                firstNonBlank(card.name(), slug),
                firstNonBlank(parsed.description(), card.description()),
                firstNonBlank(card.version(), humanizeVersion(textOrNull(detail.path("latestVersion"), "version"))),
                firstNonBlank(textOrNull(owner, "displayName"),
                        firstNonBlank(textOrNull(owner, "handle"), card.author())),
                card.tags(),
                card.updatedAt(), card.url(), parsed.body());
    }

    /**
     * 导入到平台技能库（skills 表）。名称冲突时返回 409。
     *
     * <p>git 型：把缓存目录内该技能的目录（SKILL.md + scripts/ 等全部文件）快照拷贝到
     * 平台稳定目录 {@code .skill-files/<id>}，记 origin_dir 并把目录内文本文件收进
     * resources——运行时模型可经 {@code <files-root>} 直执行脚本，load 工具也有内存
     * 资源兜底。其余仓库类型（http/clawhub/skillhub）远端 API 只暴露 SKILL.md 单文件，
     * 仅导入正文，无法携带脚本。</p>
     */
    public SkillResponse importSkill(String id) {
        SkillRepoCard card = detail(id);
        Map<String, String> resources = Map.of();
        String originDir = null;
        if (isGitType()) {
            Path snapshot = snapshotGitSkillDir(id);
            if (snapshot != null) {
                originDir = snapshot.toAbsolutePath().toString();
                resources = collectTextResources(snapshot);
            }
        }
        // 仓库导入属于平台库维护动作（skill:create 门槛在 SkillController 校验），固定落 PLATFORM
        return skillService.createPlatform(new SkillRequest(
                card.name(), card.description(), "skill", null, "skill-repository",
                card.markdown(), resources, originDir));
    }

    /** git 型之外（http/clawhub/skillhub）都归为远端目录型；refresh() 的默认分支即 git。 */
    private boolean isGitType() {
        String type = props.getType();
        return !TYPE_HTTP.equalsIgnoreCase(type) && !TYPE_CLAWHUB.equalsIgnoreCase(type)
                && !TYPE_SKILLHUB.equalsIgnoreCase(type);
    }

    /** 单个资源文件大小上限；二进制/超大文件不进 resources（originDir 磁盘上仍有）。 */
    private static final long RESOURCE_MAX_FILE_BYTES = 256 * 1024;
    /** resources 总量与文件数上限，防止巨型技能目录撑爆 JSONB 列。 */
    private static final long RESOURCE_MAX_TOTAL_CHARS = 5L * 1024 * 1024;
    private static final int RESOURCE_MAX_FILES = 200;

    /**
     * git 型导入的磁盘快照：拷贝缓存内技能目录到平台稳定目录 {@code .skill-files/<id>}
     * 并返回快照目录。不直接引用缓存目录——sync 时 {@code git clean/reset} 会动缓存，
     * 且仓库更新会让已导入技能的内容悄悄漂移；快照固定导入时刻的内容。
     */
    private Path snapshotGitSkillDir(String id) {
        Path source = resolveGitSkillDir(id);
        if (source == null) {
            log.warn("git 缓存中未找到技能目录，导入降级为纯文本技能: {}（缓存根: {}）", id, gitCacheDir());
            return null;
        }
        try {
            Path root = skillFilesRoot();
            Path target = root.resolve(id);
            if (Files.exists(target)) {
                deleteRecursively(target);
            }
            Files.createDirectories(root);
            copyRecursively(source, target);
            return target;
        } catch (IOException e) {
            log.warn("技能目录快照拷贝失败，导入降级为纯文本技能: {} ({})", id, e.getMessage());
            return null;
        }
    }

    /**
     * 定位缓存内技能目录。id 的 {@code --} 是相对路径分隔符的替换标记，但目录名本身
     * 也可能含 {@code --}——先按原始 id 试，再按解码后的相对路径试。
     */
    private Path resolveGitSkillDir(String id) {
        Path root = gitCacheDir();
        if (!Files.isDirectory(root)) {
            return null;
        }
        Path direct = root.resolve(id);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path decoded = root.resolve(id.replace(ID_SEP, "/"));
        return Files.isDirectory(decoded) ? decoded : null;
    }

    private Path skillFilesRoot() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"), ".skill-files");
        Files.createDirectories(root);
        return root;
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 收集快照目录内文本文件为资源 map（key 为相对路径，{@code /} 分隔）。二进制/超大/超量跳过。 */
    private Map<String, String> collectTextResources(Path root) {
        Map<String, String> resources = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return resources;
        }
        long total = 0;
        for (Path file : files) {
            if (resources.size() >= RESOURCE_MAX_FILES || total >= RESOURCE_MAX_TOTAL_CHARS) {
                log.warn("技能目录文件过多/过大，resources 截断: {}（已收 {} 个文件）", root, resources.size());
                break;
            }
            try {
                long size = Files.size(file);
                if (size == 0 || size > RESOURCE_MAX_FILE_BYTES) {
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                total += text.length();
                resources.put(root.relativize(file).toString().replace('\\', '/'), text);
            } catch (IOException e) {
                // 二进制或非 UTF-8 文件跳过（MalformedInputException 也走这里）
            }
        }
        return resources;
    }

    // ── 同步 ─────────────────────────────────────────────────────────────────

    private void refreshIfNeeded(boolean force) {
        if (!props.isEnabled()) {
            return;
        }
        if (force) {
            refresh(true);
            return;
        }
        Instant synced = lastSyncedAt;
        boolean fresh = synced != null && props.getCacheTtlMinutes() > 0
                && Instant.now().isBefore(synced.plusSeconds(props.getCacheTtlMinutes() * 60));
        if (fresh) {
            return;
        }
        if (cache.isEmpty()) {
            // 无缓存可服务（冷启动或上次同步失败）：只能阻塞等同步完成
            refresh(false);
        } else {
            // 有旧缓存：立即返回旧数据，后台异步刷新（远端慢时页面不再转圈干等）
            triggerAsyncRefresh();
        }
    }

    /** 触发一轮后台刷新；已有刷新在跑时直接跳过（幂等）。 */
    private void triggerAsyncRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        refreshExecutor.submit(() -> {
            try {
                refresh(false);
            } finally {
                refreshing.set(false);
            }
        });
    }

    private synchronized void refresh(boolean force) {
        String type = props.getType();
        boolean urlOptional = TYPE_CLAWHUB.equalsIgnoreCase(type)
                || TYPE_SKILLHUB.equalsIgnoreCase(type);
        // clawhub / skillhub 型 url 可缺省（用官方 API 域），git/http 型 url 必填
        if (!props.isEnabled() || (!StringUtils.hasText(props.getUrl()) && !urlOptional)) {
            return;
        }
        // 同步失败时保留旧缓存（若存在）：远端抖动不应把技能广场清空
        try {
            if (TYPE_CLAWHUB.equalsIgnoreCase(type)) {
                syncClawhub();
            } else if (TYPE_SKILLHUB.equalsIgnoreCase(type)) {
                syncSkillhub();
            } else if (TYPE_HTTP.equalsIgnoreCase(type)) {
                syncHttp();
            } else {
                syncGit();
            }
            lastSyncedAt = Instant.now();
            lastError = null;
            persistCatalog();
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("技能仓库同步失败（type={}，url={}）: {}", props.getType(),
                    maskUrl(effectiveUrl()), lastError);
            if (force) {
                throw ApiException.badRequest("技能仓库同步失败: " + lastError);
            }
            // 懒刷新失败：保留旧缓存，lastError 随 info() 暴露给前端
        }
    }

    private SkillRepoInfo toInfo() {
        return new SkillRepoInfo(props.isEnabled(),
                props.getType(), maskUrl(effectiveUrl()), props.getBranch(),
                lastSyncedAt, lastError, cache.size());
    }

    // ── 目录缓存落盘（重启不丢，远端慢也能秒开） ──────────────────────────────

    /** 同步成功后把目录写入磁盘缓存（写失败只告警，不影响内存缓存）。 */
    private void persistCatalog() {
        try {
            Path file = catalogFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, catalogJson(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("技能仓库目录缓存写盘失败（不影响内存缓存）: {}", e.getMessage());
        }
    }

    /**
     * 目录缓存文件路径：与缓存目录同级、名字 = 缓存目录名 + {@code .catalog.json}——
     * 不放进缓存目录本身，git 型 sync 的 {@code git clean -fdx} 会把它清掉。
     */
    private Path catalogFile() {
        String configured = props.getCacheDir();
        String base = StringUtils.hasText(configured)
                ? configured : System.getProperty("user.dir") + "/.skill-repository-cache";
        Path dir = Path.of(base).toAbsolutePath().normalize();
        return dir.resolveSibling(dir.getFileName() + ".catalog.json");
    }

    /**
     * 当前目录缓存序列化为 JSON（含 type 与仓库地址哈希，恢复时校验防串仓）。
     * 手工映射而非整卡反序列化：不依赖 JavaTimeModule，且 markdown 正文
     * （懒加载后的富化结果）不落盘——正文可能很大且可随时重取。
     */
    String catalogJson() {
        var cardsNode = objectMapper.createArrayNode();
        for (SkillRepoCard c : cache) {
            var n = objectMapper.createObjectNode();
            n.put("id", c.id());
            n.put("name", c.name());
            n.put("description", c.description());
            n.put("version", c.version());
            n.put("author", c.author());
            var tags = n.putArray("tags");
            c.tags().forEach(tags::add);
            if (c.updatedAt() != null) {
                n.put("updatedAt", c.updatedAt().toEpochMilli());
            }
            n.put("url", c.url());
            cardsNode.add(n);
        }
        var root = objectMapper.createObjectNode();
        root.put("type", props.getType());
        root.put("repoKey", repoKey());
        root.put("syncedAt", lastSyncedAt == null ? "" : lastSyncedAt.toString());
        root.set("cards", cardsNode);
        root.set("refs", objectMapper.valueToTree(remoteRefs));
        return root.toString();
    }

    /**
     * 应用目录缓存（磁盘恢复与单测共用）：type 与仓库地址（哈希）都和当前配置一致
     * 才生效，防止配置切换后把另一个仓库的目录当缓存用。
     */
    void applyCatalogJson(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        if (!props.getType().equalsIgnoreCase(textOrNull(root, "type"))
                || !repoKey().equals(textOrNull(root, "repoKey"))) {
            log.info("技能仓库配置已变化（type/url），丢弃磁盘目录缓存");
            return;
        }
        JsonNode cardsNode = root.path("cards");
        if (!cardsNode.isArray() || cardsNode.isEmpty()) {
            return;
        }
        List<SkillRepoCard> cards = new ArrayList<>();
        for (JsonNode c : cardsNode) {
            JsonNode ua = c.path("updatedAt");
            Instant updated = ua.canConvertToLong() ? Instant.ofEpochMilli(ua.asLong())
                    : StringUtils.hasText(ua.asText(null)) ? safeParseInstant(ua.asText()) : null;
            cards.add(new SkillRepoCard(
                    textOrNull(c, "id"), textOrNull(c, "name"), textOrNull(c, "description"),
                    textOrNull(c, "version"), textOrNull(c, "author"),
                    stringList(c.path("tags")), updated, textOrNull(c, "url"), null));
        }
        Map<String, String[]> refs = new LinkedHashMap<>();
        root.path("refs").fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            refs.put(e.getKey(), new String[] {
                    v.path(0).isMissingNode() || v.path(0).isNull() ? null : v.path(0).asText(),
                    v.path(1).isMissingNode() || v.path(1).isNull() ? null : v.path(1).asText()});
        });
        String synced = textOrNull(root, "syncedAt");
        cache = List.copyOf(cards);
        remoteRefs = Map.copyOf(refs);
        lastSyncedAt = StringUtils.hasText(synced) ? safeParseInstant(synced) : null;
    }

    /** ISO-8601 解析失败返回 null（磁盘文件被手改也不至于打挂恢复）。 */
    private static Instant safeParseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 仓库标识：effectiveUrl 的 SHA-256（不落原始 url，避免把内嵌凭证写进磁盘文件）。 */
    private String repoKey() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(effectiveUrl()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 仅供单测取 repoKey（私有实现细节的包内出口）。 */
    String catalogRepoKeyForTest() {
        return repoKey();
    }

    /** clawhub / skillhub 型 url 可缺省 → 用各自官方 API 域；其余类型原样返回。 */
    private String effectiveUrl() {
        String url = props.getUrl();
        if (StringUtils.hasText(url)) {
            return url;
        }
        if (TYPE_CLAWHUB.equalsIgnoreCase(props.getType())) {
            return DEFAULT_CLAWHUB_URL;
        }
        return TYPE_SKILLHUB.equalsIgnoreCase(props.getType()) ? DEFAULT_SKILLHUB_API : url;
    }

    private String clawhubBaseUrl() {
        String url = props.getUrl();
        String base = StringUtils.hasText(url) ? url.trim() : DEFAULT_CLAWHUB_URL;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * SkillHub API base：缺省用 {@link #DEFAULT_SKILLHUB_API}；若配置的是主站
     * skillhub.cn / www.skillhub.cn（SPA，无业务 API），自动归一化到 api 子域。
     */
    private String skillhubApiBase() {
        String url = props.getUrl();
        String base = StringUtils.hasText(url) ? url.trim() : DEFAULT_SKILLHUB_API;
        try {
            java.net.URI uri = java.net.URI.create(base);
            String host = uri.getHost();
            if ("skillhub.cn".equals(host) || "www.skillhub.cn".equals(host)) {
                base = new java.net.URI(uri.getScheme() == null ? "https" : uri.getScheme(),
                        "api.skillhub.cn", uri.getPath(), null).toString();
            }
        } catch (Exception e) {
            // 非法 url 交给后续请求时报错，这里不吞配置错误
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    // ── git 型：clone / fetch + reset 后扫描 SKILL.md ────────────────────────

    private void syncGit() throws IOException, InterruptedException {
        Path dir = gitCacheDir();
        boolean isRepo = Files.isDirectory(dir.resolve(".git"));
        if (!isRepo) {
            // 目录残留但不是仓库（上次 clone 中断等）：清空重来
            if (Files.exists(dir)) {
                deleteRecursively(dir);
            }
            Files.createDirectories(dir.getParent());
            List<String> cmd = new ArrayList<>(List.of("git", "clone", "--depth", "1"));
            if (StringUtils.hasText(props.getBranch())) {
                cmd.add("--branch");
                cmd.add(props.getBranch().trim());
            }
            cmd.add(props.getUrl().trim());
            cmd.add(dir.toString());
            runGit(cmd, "clone");
        } else {
            runGit(List.of("git", "fetch", "--depth", "1", "origin"), "fetch");
            runGit(List.of("git", "reset", "--hard", "FETCH_HEAD"), "reset");
            runGit(List.of("git", "clean", "-fdx"), "clean");
        }
        cache = scanSkillMarkdown(dir);
        log.info("技能仓库（git）同步完成，共 {} 个技能", cache.size());
    }

    private Path gitCacheDir() {
        String configured = props.getCacheDir();
        String base = StringUtils.hasText(configured)
                ? configured : System.getProperty("user.dir") + "/.skill-repository-cache";
        return Path.of(base).toAbsolutePath().normalize();
    }

    private void runGit(List<String> cmd, String step) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // 凭证缺失时直接失败，不挂起等输入
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (Stream<String> lines = process.inputReader(StandardCharsets.UTF_8).lines()) {
            output = lines.limit(200).collect(Collectors.joining("\n"));
        }
        if (!process.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git " + step + " 超时（"
                    + props.getTimeoutSeconds() + "s），请检查仓库地址与网络");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git " + step + " 失败（exit="
                    + process.exitValue() + "）: " + output);
        }
    }

    /** 扫描目录内全部 SKILL.md（跳过 .git）。 */
    List<SkillRepoCard> scanSkillMarkdown(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<SkillRepoCard> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return ".git".equals(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!SKILL_MD.equals(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    Path parent = file.getParent();
                    String relative = root.relativize(parent).toString().replace('\\', '/');
                    String id = relative.isEmpty()
                            ? slug(parent.getFileName().toString()) : slug(relative);
                    Instant updatedAt = attrs.lastModifiedTime().toInstant();
                    ParsedSkillMd parsed = parseSkillMarkdown(text,
                            parent.getFileName().toString());
                    result.add(new SkillRepoCard(id,
                            firstNonBlank(parsed.name(), parent.getFileName().toString()),
                            parsed.description(), parsed.version(), parsed.author(),
                            parsed.tags(), updatedAt, null, parsed.body()));
                } catch (Exception e) {
                    log.warn("解析 SKILL.md 失败，跳过: {} ({})", file, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.comparing(SkillRepoCard::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    /** 相对路径 → 无斜杠 id：{@code /} 与 {@code \} 替换为 {@code --}。 */
    static String slug(String relativePath) {
        return relativePath.replace("/", ID_SEP).replace("\\", ID_SEP);
    }

    // ── clawhub 型：调官方公开 API 抓目录（参照 ClawSkillHub） ────────────────

    /**
     * 抓 ClawHub 目录：{@code GET /api/v1/skills}（cursor 分页，每页最多 200），
     * 累计至 {@link #CLAWHUB_MAX_BROWSE} 或翻完为止。卡片 id = {@code owner/slug}
     * （目录端点可能不带 owner → 裸 slug），映射为无斜杠 id 后记入 side map。
     */
    private void syncClawhub() {
        String base = clawhubBaseUrl();
        List<SkillRepoCard> cards = new ArrayList<>();
        Map<String, String[]> refs = new LinkedHashMap<>();
        String cursor = null;
        int pages = 0;
        // page 上限兜底：即使上游 cursor 链正常，也不允许单次同步发出无限请求。
        int maxPages = CLAWHUB_MAX_BROWSE / 200 + 5;
        while (cards.size() < CLAWHUB_MAX_BROWSE && pages < maxPages) {
            pages++;
            String query = "/api/v1/skills?limit=200"
                    + (cursor != null ? "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) : "");
            JsonNode payload;
            try {
                payload = objectMapper.readTree(httpGet(base + query, "application/json"));
            } catch (IOException e) {
                throw new IllegalStateException("ClawHub 目录响应不是合法 JSON: " + e.getMessage());
            }
            JsonNode items = payload.path("items");
            if (!items.isArray()) {
                throw new IllegalStateException("ClawHub 目录格式不对：期望 {\"items\":[...]}");
            }
            for (JsonNode item : items) {
                SkillRepoCard card = toClawhubCard(item, base);
                if (card != null) {
                    cards.add(card);
                    refs.put(card.id(), new String[]{clawhubHandle(item), textOrNull(item, "slug")});
                }
            }
            String nextCursor = textOrNull(payload, "nextCursor");
            // 死循环防护：原实现只在 nextCursor == null 时退出——上游若回同一个 cursor
            // 且该页 0 条（或全是解析不出的条目），cards.size() 永不增长，会无限请求。
            // 现在把「翻页无进展」和「page 上限」都当作终止条件。
            if (nextCursor == null || nextCursor.equals(cursor) || items.isEmpty()) {
                if (nextCursor != null && (nextCursor.equals(cursor) || items.isEmpty())) {
                    log.warn("技能仓库（clawhub）分页未推进（cursor={} 重复或该页为空），提前结束同步",
                            nextCursor);
                }
                break;
            }
            cursor = nextCursor;
        }
        cards.sort(Comparator.comparing(SkillRepoCard::name, String.CASE_INSENSITIVE_ORDER));
        cache = List.copyOf(cards);
        remoteRefs = Map.copyOf(refs);
        log.info("技能仓库（clawhub）同步完成，共 {} 个技能", cache.size());
    }

    /** 官方目录/详情条目里的 ownerHandle：顶层或 owner 对象内。 */
    private static String clawhubHandle(JsonNode item) {
        String handle = textOrNull(item, "ownerHandle");
        if (handle != null) {
            return handle;
        }
        JsonNode owner = item.path("owner");
        return owner.isObject() ? textOrNull(owner, "handle") : null;
    }

    /**
     * 目录/搜索条目 → 卡片（对齐官方 {@code ClawSkillHub._to_card}）：
     * name 必须是裸 slug（会变成目录名）；版本号 {@code 0.YYYYMMDD.HHMMSS}
     * 人性化为日期；updatedAt 为 Unix 毫秒；tags 取 topics（上游 tags 是版本表）。
     * 无 slug 的条目返回 null 跳过。
     */
    SkillRepoCard toClawhubCard(JsonNode item, String base) {
        String slug = textOrNull(item, "slug");
        if (!StringUtils.hasText(slug)) {
            log.warn("ClawHub 条目缺少 slug，已跳过: {}", item);
            return null;
        }
        // latestVersion 在目录端点是对象、搜索端点缺省（直接给 version）
        JsonNode latest = item.path("latestVersion");
        String version = latest.isObject() ? textOrNull(latest, "version")
                : firstNonBlank(latest.isMissingNode() || latest.isNull() ? null : latest.asText(),
                        textOrNull(item, "version"));

        JsonNode owner = item.path("owner").isObject() ? item.path("owner")
                : item.path("native").path("owner");
        String handle = clawhubHandle(item);

        String canonical = textOrNull(item, "canonicalUrl");
        String url = canonical != null ? base + canonical : base + "/skills/" + slug;

        JsonNode updatedAt = item.path("updatedAt");
        Instant updated = updatedAt.canConvertToLong()
                ? Instant.ofEpochMilli(updatedAt.asLong()) : null;

        JsonNode stats = item.path("stats").isObject() ? item.path("stats")
                : objectMapper.createObjectNode();

        String id = handle != null ? slug(handle + "/" + slug) : slug(slug);
        return new SkillRepoCard(id, slug,
                textOrNull(item, "summary"),
                humanizeVersion(version),
                firstNonBlank(textOrNull(owner, "displayName"), textOrNull(owner, "handle")),
                stringList(item.path("topics")),
                updated, url, null);
    }

    /**
     * ClawHub 批量生成的版本号形如 {@code 0.20260729.110214}（17 字符噪音），
     * 按官方规则渲染成日期 {@code 2026-07-29}；其余（含正常 semver）原样返回。
     */
    static String humanizeVersion(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = Pattern.compile("^0\\.(\\d{8})\\.(\\d{6})$").matcher(version);
        if (!m.matches()) {
            return version;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1).substring(0, 4)),
                    Integer.parseInt(m.group(1).substring(4, 6)),
                    Integer.parseInt(m.group(1).substring(6, 8))).toString();
        } catch (NumberFormatException | DateTimeException e) {
            return version; // 八位数字不是真日期，原样保留
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(t -> {
            String v = t.asText(null);
            if (StringUtils.hasText(v)) {
                result.add(v);
            }
        });
        return List.copyOf(result);
    }

    // ── skillhub 型：调 SkillHub（skillhub.cn）目录 API ───────────────────────

    /**
     * 抓 SkillHub 目录：{@code GET {api}/api/skills?page=&pageSize=}（响应包
     * {@code {code,data:{skills,total}}}，page 从 1 起），累计至 total、
     * {@link #SKILLHUB_MAX_BROWSE} 或空页为止。卡片 id = {@code handle/slug}
     * 映射为无斜杠 id 后记入 side map（详情/导入回查）。
     */
    private void syncSkillhub() {
        String base = skillhubApiBase();
        List<SkillRepoCard> cards = new ArrayList<>();
        Map<String, String[]> refs = new LinkedHashMap<>();
        int total = Integer.MAX_VALUE;
        for (int page = 1; cards.size() < total && cards.size() < SKILLHUB_MAX_BROWSE; page++) {
            JsonNode data;
            try {
                String payload = httpGet(base + "/api/skills?page=" + page
                        + "&pageSize=" + SKILLHUB_PAGE_SIZE, "application/json");
                JsonNode root = objectMapper.readTree(payload);
                if (root.path("code").asInt(-1) != 0) {
                    throw new IllegalStateException("SkillHub 目录接口返回错误: "
                            + textOrNull(root, "message"));
                }
                data = root.path("data");
            } catch (IOException e) {
                throw new IllegalStateException("SkillHub 目录响应不是合法 JSON: " + e.getMessage());
            }
            JsonNode items = data.path("skills");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                SkillRepoCard card = toSkillhubCard(item);
                if (card != null) {
                    cards.add(card);
                    JsonNode ns = item.path("namespace");
                    refs.put(card.id(), new String[]{
                            ns.isObject() ? textOrNull(ns, "handle") : null,
                            firstNonBlank(ns.isObject() ? textOrNull(ns, "publicSlug") : null,
                                    textOrNull(item, "slug"))});
                }
            }
            total = data.path("total").asInt(Integer.MAX_VALUE);
        }
        cards.sort(Comparator.comparing(SkillRepoCard::name, String.CASE_INSENSITIVE_ORDER));
        cache = List.copyOf(cards);
        remoteRefs = Map.copyOf(refs);
        log.info("技能仓库（skillhub）同步完成，共 {} 个技能", cache.size());
    }

    /**
     * 目录条目 → 卡片：{@code name} 取站点展示名（如 "Word / DOCX"，无则 slug）、
     * 描述优先中文 {@code description_zh}、tags 取 {@code tags}（空则 category 兜底）、
     * updatedAt 为 Unix 毫秒；卡片 url 指向站点详情页（展示用）。无 slug 的条目
     * 返回 null 跳过。
     */
    SkillRepoCard toSkillhubCard(JsonNode item) {
        JsonNode ns = item.path("namespace");
        String handle = ns.isObject() ? textOrNull(ns, "handle") : null;
        String publicSlug = firstNonBlank(ns.isObject() ? textOrNull(ns, "publicSlug") : null,
                textOrNull(item, "slug"));
        if (!StringUtils.hasText(publicSlug)) {
            log.warn("SkillHub 条目缺少 slug，已跳过: {}", item);
            return null;
        }
        JsonNode tagsNode = item.path("tags");
        List<String> tags = tagsNode.isArray() ? stringList(tagsNode) : List.of();
        if (tags.isEmpty() && StringUtils.hasText(textOrNull(item, "category"))) {
            tags = List.of(textOrNull(item, "category"));
        }
        String id = handle != null ? slug(handle + "/" + publicSlug) : slug(publicSlug);
        String siteUrl = SKILLHUB_SITE + "/skills/"
                + (handle != null ? handle + "/" : "") + publicSlug;
        JsonNode updatedAt = item.path("updated_at");
        Instant updated = updatedAt.canConvertToLong()
                ? Instant.ofEpochMilli(updatedAt.asLong()) : null;
        return new SkillRepoCard(id,
                firstNonBlank(textOrNull(item, "name"), publicSlug),
                firstNonBlank(textOrNull(item, "description_zh"), textOrNull(item, "description")),
                textOrNull(item, "version"),
                firstNonBlank(textOrNull(item, "ownerName"),
                        ns.isObject() ? textOrNull(ns, "displayName") : null),
                tags, updated, siteUrl, null);
    }

    /**
     * skillhub 型懒加载正文：{@code GET {api}/api/v1/skills/{slug}?namespace={handle}}
     * 校验条目存在（409 = 坐标歧义），再 {@code GET .../file?path=SKILL.md&namespace=}
     * （302 → 腾讯云 COS，HttpClient NORMAL 自动跟随），frontmatter 字段兜底。
     */
    private SkillRepoCard fetchSkillhubMarkdown(SkillRepoCard card) {
        String[] ref = remoteRefs.get(card.id());
        if (ref == null) {
            throw ApiException.notFound("技能仓库缓存中没有该技能的定位信息: " + card.id());
        }
        String slug = ref[1];
        String base = skillhubApiBase();
        String nsQuery = StringUtils.hasText(ref[0])
                ? "?namespace=" + URLEncoder.encode(ref[0], StandardCharsets.UTF_8) : "";
        String skillsPath = "/api/v1/skills/" + URLEncoder.encode(slug, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> detail = newHttpClient().send(
                    newRequest(base + skillsPath + nsQuery, "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            if (detail.statusCode() == 404) {
                throw ApiException.notFound("SkillHub 上不存在该技能: " + slug);
            }
            if (detail.statusCode() == 409) {
                throw ApiException.badRequest("SkillHub 该技能坐标有歧义（409）: " + slug);
            }
            if (detail.statusCode() / 100 != 2) {
                throw new IllegalStateException("SkillHub 详情请求失败: HTTP " + detail.statusCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException("SkillHub 详情请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SkillHub 详情请求被中断", e);
        }
        ParsedSkillMd parsed = parseSkillMarkdown(
                httpGet(base + skillsPath + "/file?path=SKILL.md" + nsQuery, "text/markdown"), slug);
        return new SkillRepoCard(card.id(),
                firstNonBlank(parsed.name(), card.name()),
                firstNonBlank(parsed.description(), card.description()),
                firstNonBlank(parsed.version(), card.version()),
                firstNonBlank(card.author(), parsed.author()),
                !parsed.tags().isEmpty() ? parsed.tags() : card.tags(),
                card.updatedAt(), card.url(), parsed.body());
    }

    // ── http 型：拉取 JSON 索引 ───────────────────────────────────────────────

    private void syncHttp() {
        String body = httpGet(props.getUrl().trim(), "application/json");
        cache = parseSkillIndex(body);
        log.info("技能仓库（http）同步完成，共 {} 个技能", cache.size());
    }

    /**
     * 解析技能索引 JSON。规范格式为 {@code {"skills":[...]}}，也容忍顶层数组；
     * 每个条目必填 {@code name}，其余字段可选（见 application.yml 注释）。
     * 无 name 的条目跳过并告警，不让单条脏数据打挂整个索引。
     */
    List<SkillRepoCard> parseSkillIndex(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("技能索引不是合法 JSON: " + e.getMessage());
        }
        JsonNode items = root.isArray() ? root : root.path("skills");
        if (!items.isArray()) {
            throw new IllegalStateException("技能索引格式不对：期望 {\"skills\":[...]} 或顶层数组");
        }
        List<SkillRepoCard> result = new ArrayList<>();
        for (JsonNode item : items) {
            String name = textOrNull(item, "name");
            if (!StringUtils.hasText(name)) {
                log.warn("技能索引条目缺少 name，已跳过: {}", item);
                continue;
            }
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = item.path("tags");
            if (tagsNode.isArray()) {
                tagsNode.forEach(t -> {
                    String v = t.asText(null);
                    if (StringUtils.hasText(v)) {
                        tags.add(v);
                    }
                });
            }
            result.add(new SkillRepoCard(slug(name), name.trim(),
                    textOrNull(item, "description"), textOrNull(item, "version"),
                    textOrNull(item, "author"), List.copyOf(tags), null,
                    textOrNull(item, "url"), null));
        }
        result.sort(Comparator.comparing(SkillRepoCard::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private HttpRequest newRequest(String url, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.min(props.getTimeoutSeconds(), 60)))
                .header("Accept", accept)
                .GET();
        if (StringUtils.hasText(props.getToken())) {
            builder.header("Authorization", "Bearer " + props.getToken().trim());
        }
        return builder.build();
    }

    /**
     * GET 请求：带 Bearer token（如配置）与 429 限流重试（对齐官方
     * {@code _request}/{@code _retry_delay}——Retry-After → RateLimit-Reset →
     * X-RateLimit-Reset，抖动退避，最多 {@value #CLAWHUB_MAX_RETRIES} 次）。
     */
    private String httpGet(String url, String accept) {
        try {
            HttpClient client = newHttpClient();
            HttpRequest request = newRequest(url, accept);
            for (int attempt = 0; ; attempt++) {
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429 && attempt < CLAWHUB_MAX_RETRIES) {
                    long delayMs = (long) ((retryDelaySeconds(response.headers()) + Math.random()) * 1000);
                    log.info("技能仓库请求被限流（429），{}ms 后第 {} 次重试: {}",
                            delayMs, attempt + 1, maskUrl(url));
                    Thread.sleep(delayMs);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + "，响应前 200 字符: "
                            + response.body().substring(0, Math.min(200, response.body().length())));
                }
                return response.body();
            }
        } catch (IOException e) {
            throw new IllegalStateException("HTTP 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP 请求被中断", e);
        }
    }

    /** 从 429 响应头推算重试等待秒数；都没有时兜底 1s（对齐官方）。 */
    static double retryDelaySeconds(HttpHeaders headers) {
        String retryAfter = headers.firstValue("Retry-After").orElse(null);
        if (retryAfter != null) {
            try {
                return Math.max(0, Double.parseDouble(retryAfter));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        String reset = headers.firstValue("RateLimit-Reset").orElse(null);
        if (reset != null) {
            try {
                return Math.max(0, Double.parseDouble(reset));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        String xReset = headers.firstValue("X-RateLimit-Reset").orElse(null);
        if (xReset != null) {
            try {
                return Math.max(0, Double.parseDouble(xReset) - Instant.now().getEpochSecond());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 1.0;
    }

    // ── 通用 ─────────────────────────────────────────────────────────────────

    private List<SkillRepoCard> filter(List<SkillRepoCard> cards, String search) {
        if (!StringUtils.hasText(search)) {
            return cards;
        }
        String needle = search.trim().toLowerCase();
        return cards.stream()
                .filter(c -> (c.name() != null && c.name().toLowerCase().contains(needle))
                        || (c.description() != null && c.description().toLowerCase().contains(needle))
                        || c.tags().stream().anyMatch(t -> t.toLowerCase().contains(needle)))
                .toList();
    }

    private SkillRepoCard findById(String id) {
        return cache.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    // ── SKILL.md frontmatter 解析（静态，便于单测） ──────────────────────────

    /** frontmatter 解析结果；body 为 frontmatter 之后的正文。 */
    record ParsedSkillMd(String name, String description, String version,
                         String author, List<String> tags, String body) {
    }

    private static final Pattern FRONTMATTER_OPEN = Pattern.compile("^---\\s*$");
    private static final Pattern FRONTMATTER_CLOSE = Pattern.compile("^(---|\\.\\.\\.)\\s*$");

    /**
     * 解析 SKILL.md：可选 YAML frontmatter（仅取 name/description/version/author/tags
     * 这几个平铺键）+ 正文。无 frontmatter 时 body 为原文、name 为 null（调用方用目录名兜底）。
     */
    static ParsedSkillMd parseSkillMarkdown(String text, String fallbackName) {
        if (text == null) {
            return new ParsedSkillMd(null, null, null, null, List.of(), "");
        }
        String normalized = text.replace("\r\n", "\n");
        if (normalized.startsWith("﻿")) {
            normalized = normalized.substring(1);
        }
        String[] lines = normalized.split("\n", -1);
        if (lines.length > 0 && FRONTMATTER_OPEN.matcher(lines[0]).matches()) {
            for (int i = 1; i < lines.length; i++) {
                if (FRONTMATTER_CLOSE.matcher(lines[i]).matches()) {
                    Map<String, String> meta = parseFrontmatter(
                            Arrays.copyOfRange(lines, 1, i));
                    return new ParsedSkillMd(
                            trimOrNull(meta.get("name")),
                            trimOrNull(meta.get("description")),
                            trimOrNull(meta.get("version")),
                            trimOrNull(meta.get("author")),
                            parseTags(meta.get("tags")),
                            String.join("\n",
                                    Arrays.copyOfRange(lines, i + 1, lines.length))
                                    .stripLeading());
                }
            }
        }
        // 无 frontmatter：name 用目录名兜底，body 全文
        return new ParsedSkillMd(trimOrNull(fallbackName), null, null, null, List.of(), normalized);
    }

    /** 平铺 key: value；不支持的 key 忽略，重复 key 后者覆盖。 */
    private static Map<String, String> parseFrontmatter(String[] lines) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx <= 0 || line.startsWith("#") || line.startsWith(" ") || line.startsWith("\t")) {
                continue; // 跳过注释/嵌套键，本解析只认平铺键
            }
            String key = line.substring(0, idx).trim().toLowerCase();
            String value = line.substring(idx + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1); // 内联数组留给 parseTags 拆
            }
            meta.put(key, value);
        }
        return meta;
    }

    /** tags 支持 YAML 内联数组 {@code [a, b]} 与逗号分隔两种写法。 */
    static List<String> parseTags(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Stream.of(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null
                : (StringUtils.hasText(v.asText()) ? v.asText().trim() : null);
    }

    /** 日志里隐去 url 中可能内嵌的凭证。 */
    static String maskUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return url;
        }
        return url.replaceAll("(?<=//)([^/@:/]+):([^/@]+)@", "$1:***@");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
