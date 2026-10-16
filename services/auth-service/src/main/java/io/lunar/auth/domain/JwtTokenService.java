package io.lunar.auth.domain;

import io.lunar.auth.domain.dto.IssuedToken;
import io.lunar.common.security.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * RS256 访问令牌签发，claims 契约见 docs/api.md"认证令牌"：
 * sub=用户 ID、roles=角色数组、iss=auth-service、iat/exp、jti（预留吊销）。
 */
@Component
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final Duration accessTtl;
    /** 令牌受众（BE-09）：资源服务会校验 aud，缺了它一律 401。 */
    private final String audience;

    public JwtTokenService(JwtEncoder jwtEncoder,
                           @Value("${auth.jwt.access-ttl-minutes:30}") long accessTtlMinutes,
                           @Value("${auth.jwt.audience:lunar-api}") String audience) {
        this.jwtEncoder = jwtEncoder;
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
        this.audience = audience;
    }

    public IssuedToken issue(String userId, Role role) {
        return issue(userId, role, accessTtl);
    }

    /**
     * 服务身份令牌（客户端凭证）：sub=服务名（如 contest-service）、roles=["SERVICE"]，
     * TTL 由调用方给定（比用户令牌短，泄露窗口更小）。
     */
    public IssuedToken issueServiceToken(String clientId, Duration ttl) {
        return issue(clientId, Role.SERVICE, ttl);
    }

    private IssuedToken issue(String subject, Role role, Duration ttl) {
        // JWT 时间声明为秒级精度，这里统一截断，保证返回的 expiresAt 与令牌 exp 一致
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("auth-service")
                .subject(subject)
                .audience(List.of(audience))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("roles", List.of(role.name()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
