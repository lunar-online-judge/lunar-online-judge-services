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

    public JwtTokenService(JwtEncoder jwtEncoder,
                           @Value("${auth.jwt.access-ttl-minutes:30}") long accessTtlMinutes) {
        this.jwtEncoder = jwtEncoder;
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
    }

    public IssuedToken issue(String userId, Role role) {
        // JWT 时间声明为秒级精度，这里统一截断，保证返回的 expiresAt 与令牌 exp 一致
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(accessTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("auth-service")
                .subject(userId)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("roles", List.of(role.name()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
