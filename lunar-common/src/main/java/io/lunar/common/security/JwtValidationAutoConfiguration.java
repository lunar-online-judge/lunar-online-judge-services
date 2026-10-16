package io.lunar.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * 统一的 JWT 校验（后续后端优化计划 BE-09）：除签名与 exp/nbf 外，还校验 <b>issuer</b> 与 <b>audience</b>。
 *
 * 为什么必须显式校验：各资源服务原本只配了 {@code jwk-set-uri}，于是只验签名与时间——
 * 任何用同一把密钥签出的令牌都能通过，issuer/audience 形同装饰；一旦将来出现第二套签发方
 * （多环境共用 JWKS、临时脚本签发的排障令牌），就没有任何东西能把它挡住。
 *
 * 默认开启（{@code matchIfMissing = true}），可用 {@code auth.jwt.strict-validation=false} 临时关闭；
 * 已有自定义 {@link JwtDecoder} 的服务不受影响（{@code @ConditionalOnMissingBean}）。
 */
@AutoConfiguration
@ConditionalOnClass(JwtDecoder.class)
@ConditionalOnProperty(prefix = "auth.jwt", name = "strict-validation", havingValue = "true", matchIfMissing = true)
public class JwtValidationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    // 没有配 JWKS 的服务（如 judge，它不走资源服务器）不该因为缺这个属性起不来：条件放在方法上
    @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${auth.jwt.issuer:auth-service}") String issuer,
            @Value("${auth.jwt.audience:lunar-api}") String audience,
            @Value("${auth.jwt.revocation-cache-seconds:30}") long revocationCacheSeconds) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                // 用 Jwt#getAudience()（已经是 List<String>）而不是 JwtClaimValidator<List<String>>：
                // 单元素 aud 会被 Nimbus 解成 String，泛型校验器强转会抛 ClassCastException（实测会变成 500）
                token -> token.getAudience() != null && token.getAudience().contains(audience)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "令牌受众不匹配", null)),
                new TokenRevocationValidator(RestClient.create(), tokensValidAfterUri(jwkSetUri),
                        Duration.ofSeconds(revocationCacheSeconds)));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * 由 JWKS 地址推导失效时间线查询地址：两者同属 auth 服务，用同一个 host:port 即可，
     * 避免再引入一个必须与 JWKS 保持一致的配置项（不一致会导致吊销静默失效）。
     */
    private static String tokensValidAfterUri(String jwkSetUri) {
        URI jwkSet = URI.create(jwkSetUri);
        return jwkSet.getScheme() + "://" + jwkSet.getAuthority()
                + "/api/v1/internal/users/{userId}/tokens-valid-after";
    }
}
