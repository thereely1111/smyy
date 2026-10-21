package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.AuthProperties;
import com.tipdm.agentplatform.config.DocumentPreviewProperties;
import com.tipdm.agentplatform.util.RedisKeyConstants;
import com.tipdm.agentplatform.util.RedisUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 预览文件回拉票据：让<b>预览服务</b>（TipDM-FV）能取到平台上的文件字节。
 *
 * <p><strong>为什么需要它</strong>：TipDM-FV 的接口只接受一个 {@code url} 参数，
 * 由它自己发起下载。而平台的文件在本地磁盘上、且不暴露给外部，
 * 所以必须提供一个「凭票据放行、只读单个文件」的窄门给预览服务回拉。
 * 与 {@link DownloadTicketService} 是同一思路、但<b>面向不同的调用方</b>：
 * 后者给浏览器（原生下载链接挂不了 Authorization 头），本票据给服务端程序。</p>
 *
 * <p><strong>票据是降权而非提权</strong>：权限严格小于 access token——单次、极短时效、
 * 绑定到<b>具体空间+具体相对路径</b>。拿到票据只能读那一个文件，
 * 且票据本身不含用户身份语义（回拉端点由预览服务匿名调用，无从判权，
 * 因此权限判定必须在此之前完成，见 {@code DocumentPreviewTool}）。</p>
 *
 * <p><strong>真单次</strong>：同 {@code DownloadTicketService}——用 Redis SETNX 记录
 * 已消费的 jti，并发重放只有一个能过；Redis 不可用时<b>拒绝放行</b>（fail-close）。</p>
 *
 * <p><strong>两类来源</strong>：预览的文件可能来自两个毫不相干的存储——资料库 drive
 * （{@code blobStore}，按 spaceId + 相对路径寻址）与会话产物（会话磁盘目录，
 * 按 agentId + sessionId + 相对路径寻址，且带子会话白名单与符号链接防护）。
 * 二者的寻址参数不兼容，因此票据带 {@link #KIND} 判别位，
 * {@link #consume} 消费时返回对应形态的载荷，由回拉端点按类型取字节。</p>
 */
@Service
public class PreviewTicketService {

    private static final Logger log = LoggerFactory.getLogger(PreviewTicketService.class);

    /**票据 claim 里的 type 值。刻意区别于 access/download：JwtAuthFilter 只接受 access。 */
    public static final String TICKET_TYPE = "preview";

    private static final String CLAIM_SPACE_ID = "spc";
    private static final String CLAIM_REL_PATH = "pth";
    private static final String CLAIM_FILE_NAME = "fnm";

    /** 票据 claim 里的来源判别位。 */
    private static final String CLAIM_KIND = "knd";

    /** 来源：资料库 drive 文件。 */
    public static final String KIND_LIBRARY = "lib";

    /** 来源：会话产物文件。 */
    public static final String KIND_ARTIFACT = "art";

    private static final String CLAIM_AGENT_ID = "agd";
    private static final String CLAIM_SESSION_ID = "ses";

    private final SecretKey signingKey;
    private final RedisUtils redisUtils;
    private final Duration ttl;

    public PreviewTicketService(AuthProperties authProperties,
                                RedisUtils redisUtils,
                                DocumentPreviewProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(
                authProperties.getJwtSecretKey().getBytes(StandardCharsets.UTF_8));
        this.redisUtils = redisUtils;
        this.ttl = properties.ticketTtl();
    }

    /**
     * 签发一张绑定到具体文件路径的回拉票据（资料库来源）。
     *
     * @param spaceId      资料库空间 ID
     * @param relativePath 空间内的相对路径（{@code LibraryBlobStore} 口径）
     * @param fileName     原始文件名（票据载荷，回拉时按它还原带后缀的文件名）
     */
    public String issue(UUID spaceId, String relativePath, String fileName) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(jti)
                .id(jti)
                .issuer(JwtService.ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claim("type", TICKET_TYPE)
                .claim(CLAIM_KIND, KIND_LIBRARY)
                .claim(CLAIM_SPACE_ID, spaceId.toString())
                .claim(CLAIM_REL_PATH, relativePath)
                .claim(CLAIM_FILE_NAME, fileName == null ? "" : fileName)
                .signWith(signingKey)
                .compact();
        log.info("签发预览回拉票据 space={} path={} file={} jti={} ttl={}s",
                spaceId, relativePath, fileName, jti, ttl.toSeconds());
        return token;
    }

    /**
     * 签发一张绑定到<b>会话产物文件</b>的回拉票据。
     *
     * <p>产物不在 {@code blobStore} 里，因此不带 spaceId，改为绑定
     * agentId + sessionId + 会话内相对路径。回拉端点据此走
     * {@code SessionArtifactController.resolveArtifactFile}——复用它那套子会话白名单
     * 与真实路径（符号链接）校验，而不是在这里另写一份解析。</p>
     *
     * <p><b>票据带userId</b>（与 {@code DownloadTicketService} 同惯例）：产物路径可能以
     * 子会话 id 为首段，而子会话白名单要按 userId 查询。没有它，回拉端点只能按空白名单
     * 解析，结果是「子会话产物在面板里点得开、预览服务却永远拉不到」——
     * 签票成功而回拉 404，比直接拒绝更难排查。</p>
     *
     * <p>⚠️ 调用方必须在签发<b>之前</b>完成归属校验与文件解析：票据是窄权限凭据，
     * 即便带了 userId，回拉端点也不再具备拒绝越权的能力（它无从判断该用户此刻是否仍有权）。</p>
     *
     * @param userId       发起人（仅用于回拉时查子会话白名单，不承载判权语义）
     * @param agentId      智能体 id
     * @param sessionId    会话 id
     * @param relativePath 会话内相对路径（产物树口径，可能带子会话首段）
     * @param fileName     原始文件名（含后缀，预览服务靠它判断渲染方式）
     */
    public String issueArtifact(String userId, UUID agentId, String sessionId,
                                String relativePath, String fileName) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(userId)
                .id(jti)
                .issuer(JwtService.ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claim("type", TICKET_TYPE)
                .claim(CLAIM_KIND, KIND_ARTIFACT)
                .claim(CLAIM_AGENT_ID, agentId.toString())
                .claim(CLAIM_SESSION_ID, sessionId)
                .claim(CLAIM_REL_PATH, relativePath)
                .claim(CLAIM_FILE_NAME, fileName == null ? "" : fileName)
                .signWith(signingKey)
                .compact();
        log.info("签发产物预览回拉票据 agent={} session={} path={} file={} jti={} ttl={}s",
                agentId, sessionId, relativePath, fileName, jti, ttl.toSeconds());
        return token;
    }

    /**
     * 校验并<b>原子消费</b>一张票据：验签 → 校验类型/时效 → SETNX 标记已用。
     *
     * <p>按 {@link #CLAIM_KIND} 返回对应来源的载荷；kind 缺失或不受支持时按资料库
     * 口径处理（存量票据没有该claim），因此消费方必须穷举 {@link Payload.Source}。</p>
     *
     * <p>⚠️ <b>不重复使用</b>：TipDM-FV 拿到 {@code url} 后只会回拉一次，
     * 若它内部重试需要重新签发（上层在调预览接口时重新 issue 即可）。</p>
     *
     * @param token 待核销的票据
     * @return 票据载荷；无效/过期/已使用/Redis 不可用均返回 {@link Optional#empty()}
     */
    public Optional<Payload> consume(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(JwtService.ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("预览票据验签失败: {}", e.getMessage());
            return Optional.empty();
        }

        if (!TICKET_TYPE.equals(claims.get("type", String.class))) {
            return Optional.empty();
        }
        Date exp = claims.getExpiration();
        if (exp == null || !exp.toInstant().isAfter(Instant.now())) {
            return Optional.empty();
        }

        String relPath = claims.get(CLAIM_REL_PATH, String.class);
        if (relPath == null || relPath.isBlank()) {
            return Optional.empty();
        }
        String kind = claims.get(CLAIM_KIND, String.class);
        if (KIND_ARTIFACT.equals(kind)) {
            // 产物来源：认agentId + sessionId，两项缺一即拒——宁可失败也不能退回按资料库解析
            String agentId = claims.get(CLAIM_AGENT_ID, String.class);
            String sessionId = claims.get(CLAIM_SESSION_ID, String.class);
            if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
                return Optional.empty();
            }
            UUID parsedAgentId;
            try {
                parsedAgentId = UUID.fromString(agentId);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            Payload payload = new Payload(fileNameOf(claims), relPath,
                    new Payload.ArtifactSource(parsedAgentId, sessionId, userIdOf(claims)));
            if (!markUsed(claims, parsedAgentId.toString())) {
                return Optional.empty();
            }
            log.info("消费产物预览票据 agent={} session={} path={} file={}",
                    parsedAgentId, sessionId, relPath, payload.fileName());
            return Optional.of(payload);
        }
        String spaceId = claims.get(CLAIM_SPACE_ID, String.class);
        if (spaceId == null || spaceId.isBlank()) {
            return Optional.empty();
        }
        Payload payload = new Payload(fileNameOf(claims), relPath,
                new Payload.LibrarySource(spaceId));
        if (!markUsed(claims, spaceId)) {
            return Optional.empty();
        }
        log.info("消费预览票据 space={} path={} file={}", spaceId, relPath, payload.fileName());
        return Optional.of(payload);
    }

    /**
     * 标记票据已消费（真单次）。
     *
     * <p>Redis 不可用时返回 {@code false}（fail-close），不放行。</p>
     *
     * @param claims     已验签的载荷
     * @param markerValue 写入 Redis 的标记值（来源标识，便于运维排查）
     * @return 首次消费（成功标记）时为 {@code true}
     */
    private boolean markUsed(Claims claims, String markerValue) {
        String jti = claims.getId();
        if (jti == null) {
            return false;
        }
        String key = RedisKeyConstants.previewTicketUsedKey(jti);
        try {
            Boolean fresh = redisUtils.getRedisTemplate()
                    .opsForValue()
                    .setIfAbsent(key, markerValue);
            if (!Boolean.TRUE.equals(fresh)) {
                log.warn("预览票据重复使用（已消费）: jti={}", jti);
                return false;
            }
        } catch (RuntimeException e) {
            // fail-close：Redis 不可用时拒绝放行
            log.error("预览票据单次校验失败（Redis 不可用，fail-close）: {}", e.getMessage());
            return false;
        }
        redisUtils.expire(key, ttl.toSeconds(), TimeUnit.SECONDS);
        return true;
    }

    private static String fileNameOf(Claims claims) {
        String fileName = claims.get(CLAIM_FILE_NAME, String.class);
        return fileName == null ? "" : fileName;
    }

    private static String userIdOf(Claims claims) {
        String userId = claims.getSubject();
        return userId == null ? "" : userId;
    }

    /**
     * 已核销的票据载荷。
     *
     * @param fileName 原始文件名（可能为空串）
     * @param relativePath 相对路径（按 {@link #source()} 的口径解释）
     * @param source 来源（资料库 drive / 会话产物）
     */
    public record Payload(String fileName, String relativePath, Source source) {

        /**
         * 票据来源。
         *
         * <p>做成 sealed：新增来源必须让 {@link #source()} 的消费方穷举处理，
         * 不会因为漏写分支而静默走错取数路径。</p>
         */
        public sealed interface Source permits LibrarySource, ArtifactSource {
        }

        /** 资料库 drive 文件：按空间 + 相对路径寻址。 */
        public record LibrarySource(String spaceId) implements Source {
        }

        /** 会话产物文件：按智能体 + 会话 + 会话内相对路径寻址。 */
        public record ArtifactSource(UUID agentId, String sessionId, String userId)
                implements Source {
        }
    }
}
