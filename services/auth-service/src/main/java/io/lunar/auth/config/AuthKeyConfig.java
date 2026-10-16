package io.lunar.auth.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * RS256 密钥装配：{@code AUTH_JWT_PRIVATE_KEY}（PKCS#8 PEM，可含头尾行）优先；未配置时临时生成开发密钥——
 * 重启后已签发令牌全部失效，多实例互不认，**生产必须外部注入**（prod profile 由
 * {@link ProductionConfigGate} 直接拒绝启动，密钥禁止入库）。
 *
 * 轮换（BE-02）：JWKS 与验签同时接受"当前密钥 + {@link JwtKeyProperties} 里的退役公钥"，
 * 换密钥后旧 token 在过期前仍可用；签发只用当前密钥，新 token 自然带新 kid。
 * 密钥只在 {@link JwtKeyMaterial} 单一来源构造，编码器与 JWKS 必须同源。
 */
@Configuration
public class AuthKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthKeyConfig.class);

    @Bean
    public JwtKeyMaterial keyMaterial(@Value("${auth.jwt.private-key:}") String privateKeyPem,
                                      @Value("${auth.jwt.key-id:lunar-dev-1}") String keyId) throws Exception {
        RSAPrivateCrtKey privateKey = parseOrGenerate(privateKeyPem);
        RSAPublicKey publicKey = derivePublicKey(privateKey);
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new JwtKeyMaterial(rsaKey, publicKey);
    }

    /** 验签与 JWKS 共用的公钥集合：当前公钥在前，其后是轮换窗口内的退役公钥。 */
    @Bean
    public List<JWK> verificationKeys(JwtKeyMaterial keyMaterial, JwtKeyProperties keyProperties) throws Exception {
        List<JWK> keys = new ArrayList<>();
        keys.add(keyMaterial.rsaKey().toPublicJWK());
        for (Map.Entry<String, String> retired : keyProperties.getRetiredPublicKeys().entrySet()) {
            keys.add(new RSAKey.Builder(parsePublicKey(retired.getValue()))
                    .keyID(retired.getKey())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build());
        }
        if (!keyProperties.getRetiredPublicKeys().isEmpty()) {
            log.info("密钥轮换窗口生效：JWKS 同时发布 {} 个退役公钥（旧 token 到期后应移除配置）",
                    keyProperties.getRetiredPublicKeys().size());
        }
        return keys;
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtKeyMaterial keyMaterial) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keyMaterial.rsaKey())));
    }

    /**
     * 验签自身签发的令牌（服务间接口 {@code /api/v1/internal/**} 的鉴权基础）。
     * 用完整公钥集合而不是单把公钥：轮换窗口内旧 token 在内部接口上同样有效。
     */
    @Bean
    public JwtDecoder jwtDecoder(List<JWK> verificationKeys) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(new JWKSet(verificationKeys))));
        return new NimbusJwtDecoder(processor);
    }

    /** JWKS 端点内容：仅含公钥（当前 + 退役）。 */
    @Bean
    public Map<String, Object> publicJwkSet(List<JWK> verificationKeys) {
        return new JWKSet(verificationKeys).toJSONObject();
    }

    private static RSAPrivateCrtKey parseOrGenerate(String privateKeyPem) throws Exception {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            log.warn("未配置 AUTH_JWT_PRIVATE_KEY，使用临时生成的开发密钥：重启后已签发令牌全部失效，生产必须外部注入");
            KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            return (RSAPrivateCrtKey) pair.getPrivate();
        }
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(decodePem(privateKeyPem)));
    }

    private static RSAPublicKey parsePublicKey(String publicKeyPem) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decodePem(publicKeyPem)));
    }

    private static byte[] decodePem(String pem) {
        return Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]*-----", "").replaceAll("\\s", ""));
    }

    private static RSAPublicKey derivePublicKey(RSAPrivateCrtKey privateKey) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPublicKeySpec spec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
        return (RSAPublicKey) factory.generatePublic(spec);
    }
}
