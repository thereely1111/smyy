package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.AuthProperties;
import com.tipdm.agentplatform.util.RedisKeyConstants;
import com.tipdm.agentplatform.util.RedisUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * 产物下载票据：让浏览器原生下载（{@code <a href>} / 导航）能通过鉴权。
 *
 * <p><strong>为什么需要它</strong>：JWT 只走 {@code Authorization} 请求头
 * （见 {@code JwtAuthFilter}），而 {@code <a href download>} 无法附加自定义头；
 * 把 access token 塞进 URL 则等于写进浏览器历史、Referer 与 nginx 访问日志。
 * 票据是这两者之间的窄门：前端先用正常请求头换一张短时凭据，
 * 再交给浏览器直接下载，全程不暴露 access token。</p>
 *
 * <p><strong>票据是降权而非提权</strong>：权限严格小于 access token——单次、极短时效、
 * 且绑定到具体会话与具体产物路径。拿到票据只能下那一个路径的文件，
 * 且必须先通过 {@code SessionAccessGuard} 的归属校验。</p>
 *
 * <p><strong>真单次</strong>：签名串天然可重放，因此用 Redis SETNX 记录已消费的 jti，
 * 原子写入成功才放行——并发重放也只有一个能过。Redis 不可用时**拒绝放行**（fail-close），
 * 宁可下载不可用，也不降级成可重放。</p>
 */
@Service
public class DownloadTicketService {

    private static final Logger log = LoggerFactory.getLogger(DownloadTicketService.class);

    /** 票据 claim 里的 type 值。刻意区别于 access/refresh：JwtAuthFilter只接受 access。 */
    public static final String TICKET_TYPE = "download";

    private static final String CLAIM_AGENT_ID = "aid";
    private static final String CLAIM_SESSION_ID = "sid";
    private static final String CLAIM_PATH = "pth";
    private static final String CLAIM_FILE_NAME = "fnm";

    private final SecretKey signingKey;
    private final RedisUtils redisUtils;
    private final Duration ttl;

    public DownloadTicketService(AuthProperties authProperties,
                                 RedisUtils redisUtils,
                                 @Value("${agent-platform.artifacts-download.ticket-ttl-seconds:60}")
                                 long ticketTtlSeconds) {
        this.signingKey = Keys.hmacShaKeyFor(
                authProperties.getJwtSecretKey().getBytes(StandardCharsets.UTF_8));
        this.redisUtils = redisUtils;
        // 60 秒足够「换票 → 发起下载」这一步；更长的窗口只是徒增重放收益
        this.ttl = Duration.ofSeconds(Math.max(10, ticketTtlSeconds));
    }

    /**
     * 签发一张绑定到具体产物路径的下载票据。
     *
     * @param userId    发起人（票据即身份，Controller 侧仍需按此校验归属）
     * @param agentId   智能体 ID
     * @param sessionId 会话 ID
     * @param path      会话内产物相对路径；空串表示「合并树全量」
     * @param fileName  建议的下载文件名（响应头用，不参与鉴权）
     */
    public String issue(String userId, UUID agentId, String sessionId, String path, String fileName) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(userId)
                .id(jti)
                .issuer(JwtService.ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claim("type", TICKET_TYPE)
                .claim(CLAIM_AGENT_ID, agentId.toString())
                .claim(CLAIM_SESSION_ID, sessionId)
                .claim(CLAIM_PATH, path)
                .claim(CLAIM_FILE_NAME, fileName)
                .signWith(signingKey)
                .compact();
        log.info("签发下载票据 user={} agent={} session={} path={} jti={}",
                userId, agentId, sessionId, path, jti);
        return token;
    }

    /**
     * 校验并**原子消费**一张票据：验签 → 校验类型/时效 → SETNX 标记已用。
     *
     * <p>SETNX 是并发的唯一闸门：两个请求同时用一张票据，只有一个能写入成功。
     * 重复使用（含用户重复点击、浏览器重试）返回 empty。</p>
     *
     * @param expectedAgentId   当前下载请求的 agentId，须与票据一致
     * @param expectedSessionId 当前下载请求的 sessionId，须与票据一致
     * @return 票据载荷；无效/过期/已使用/Redis 不可用均返回 {@link Optional#empty()}
     */
    public Optional<Ticket> consume(String token, UUID expectedAgentId, String expectedSessionId) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(JwtService.ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("下载票据验签失败: {}", e.getMessage());
            return Optional.empty();
        }

        if (!TICKET_TYPE.equals(claims.get("type", String.class))) {
            return Optional.empty();
        }
        Date exp = claims.getExpiration();
        if (exp == null || !exp.toInstant().isAfter(Instant.now())) {
            return Optional.empty();
        }

        // 票据绑定到具体会话：拿 A 会话的票据下不了 B 会话的产物
        String agentId = claims.get(CLAIM_AGENT_ID, String.class);
        String sessionId = claims.get(CLAIM_SESSION_ID, String.class);
        if (agentId == null || !agentId.equals(expectedAgentId.toString())
                || sessionId == null || !sessionId.equals(expectedSessionId)) {
            log.warn("下载票据会话不匹配: ticket=({}/{}) request=({}/{})",
                    agentId, sessionId, expectedAgentId, expectedSessionId);
            return Optional.empty();
        }

        // 真单次：原子写入「已消费」标记，并发下只有一个能成功
        String jti = claims.getId();
        if (jti == null) {
            return Optional.empty();
        }
        String key = RedisKeyConstants.downloadTicketUsedKey(jti);
        try {
            // SETNX 语义：只有「此前不存在」才写入成功，是并发下的唯一闸门
            Boolean fresh = redisUtils.getRedisTemplate()
                    .opsForValue()
                    .setIfAbsent(key, expectedSessionId);
            if (!Boolean.TRUE.equals(fresh)) {
                log.warn("下载票据重复使用（已消费）: jti={}", jti);
                return Optional.empty();
            }
        } catch (RuntimeException e) {
            // fail-close：Redis 不可用时拒绝放行。降级成可重放等于把安全底线让出去
            log.error("下载票据单次校验失败（Redis 不可用，fail-close）: {}", e.getMessage());
            return Optional.empty();
        }
        // TTL 与票据本身一致，到期自动清理，无需回收任务
        redisUtils.expire(key, ttl.toSeconds(), TimeUnit.SECONDS);

        String userId = claims.getSubject();
        String path = claims.get(CLAIM_PATH, String.class);
        String fileName = claims.get(CLAIM_FILE_NAME, String.class);
        log.info("消费下载票据 user={} session={} path={}", userId, expectedSessionId, path);
        return Optional.of(new Ticket(userId, path == null ? "" : path,
                fileName == null ? "" : fileName, ttl.toSeconds()));
    }

    /** 已核销的票据载荷。 */
    public record Ticket(String userId, String path, String fileName, long expiresInSeconds) {
    }
}
