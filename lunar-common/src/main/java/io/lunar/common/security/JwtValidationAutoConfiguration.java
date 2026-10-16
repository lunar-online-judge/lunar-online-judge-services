package io.lunar.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

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
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${auth.jwt.issuer:auth-service}") String issuer,
            @Value("${auth.jwt.audience:lunar-api}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audiences -> audiences != null && audiences.contains(audience)));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
