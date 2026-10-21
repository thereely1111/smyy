package com.tipdm.agentplatform.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.tipdm.agentplatform.config.AuthProperties;
import com.tipdm.agentplatform.domain.User;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * JWT 签发与校验（HS256）。payload 结构与参考项目 agent-platform 保持一致：
 * sub / username / role / roles / permissions / auth_type / iss=agent-platform / type=access|refresh。
 */
@Service
public class JwtService {

    public static final String ISSUER = "agent-platform";

    private final AuthProperties properties;
    private final SecretKey signingKey;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.getJwtSecretKey().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(User user) {
        return createToken(user, "access", properties.getAccessExpireMinutes() * 60);
    }

    public String createRefreshToken(User user) {
        return createToken(user, "refresh", properties.getRefreshExpireDays() * 24 * 60 * 60);
    }

    private String createToken(User user, String type, long ttlSeconds) {
        Instant now = Instant.now();
        Instant expire = now.plusSeconds(ttlSeconds);
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name().toLowerCase())
                .claim("roles", List.of(user.getRole().name().toLowerCase()))
                .claim("permissions", List.of())
                .claim("auth_type", user.getAuthType())
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expire))
                .claim("type", type)
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解码并验证 JWT。无效/过期/签发者不符返回 empty。
     */
    public Optional<TokenPayload> decode(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String type = claims.get("type", String.class);
            if (type == null) {
                return Optional.empty();
            }
            return Optional.of(new TokenPayload(
                    claims.getSubject(),
                    claims.get("username", String.class),
                    claims.get("role", String.class),
                    type,
                    claims.getExpiration() != null ? claims.getExpiration().toInstant() : null));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record TokenPayload(String userId, String username, String role, String type, Instant expiresAt) {
    }
}
