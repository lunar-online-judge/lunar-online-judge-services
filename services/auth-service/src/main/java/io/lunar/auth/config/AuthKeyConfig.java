package io.lunar.auth.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * RS256 密钥装配：AUTH_JWT_PRIVATE_KEY（PKCS#8 PEM，可含头尾行）优先；未配置时临时生成开发密钥——
 * 重启后已签发令牌全部失效，生产必须外部注入（密钥禁止入库）。
 * 密钥只在 {@link KeyMaterial} 单一来源构造，编码器与 JWKS 必须同源。
 */
@Configuration
public class AuthKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthKeyConfig.class);

    /** 完整 RSA 密钥（含私钥），签发与公钥分发的唯一来源。 */
    record KeyMaterial(RSAKey rsaKey) {}

    @Bean
    public KeyMaterial keyMaterial(@Value("${auth.jwt.private-key:}") String privateKeyPem,
                                   @Value("${auth.jwt.key-id:lunar-dev-1}") String keyId) throws Exception {
        RSAPrivateCrtKey privateKey = parseOrGenerate(privateKeyPem);
        RSAPublicKey publicKey = derivePublicKey(privateKey);
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new KeyMaterial(rsaKey);
    }

    @Bean
    public JwtEncoder jwtEncoder(KeyMaterial keyMaterial) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keyMaterial.rsaKey())));
    }

    /** JWKS 端点内容：仅含公钥。 */
    @Bean
    public Map<String, Object> publicJwkSet(KeyMaterial keyMaterial) {
        return new JWKSet(keyMaterial.rsaKey().toPublicJWK()).toJSONObject();
    }

    private static RSAPrivateCrtKey parseOrGenerate(String privateKeyPem) throws Exception {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            log.warn("未配置 AUTH_JWT_PRIVATE_KEY，使用临时生成的开发密钥：重启后已签发令牌全部失效，生产必须外部注入");
            KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            return (RSAPrivateCrtKey) pair.getPrivate();
        }
        String base64 = privateKeyPem.replaceAll("-----[A-Z ]*-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAPublicKey derivePublicKey(RSAPrivateCrtKey privateKey) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPublicKeySpec spec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
        return (RSAPublicKey) factory.generatePublic(spec);
    }
}
