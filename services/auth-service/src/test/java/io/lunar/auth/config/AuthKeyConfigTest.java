package io.lunar.auth.config;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密钥轮换契约（BE-02）：JWKS 同时发布当前与退役公钥；退役密钥签发的 token 在轮换窗口内仍能验签；
 * JWKS 只含公钥；未知密钥签发的 token 被拒。
 */
class AuthKeyConfigTest {

    private final AuthKeyConfig authKeyConfig = new AuthKeyConfig();

    @Test
    void jwksPublishesCurrentAndRetiredKeysAndNoPrivateMaterial() throws Exception {
        KeyPair retiredPair = generateKeyPair();
        JwtKeyMaterial keyMaterial = authKeyConfig.keyMaterial(
                privateKeyPem(generateKeyPair()), "kid-current");
        JwtKeyProperties keyProperties = new JwtKeyProperties();
        keyProperties.setRetiredPublicKeys(Map.of("kid-retired", publicKeyPem(retiredPair)));

        List<JWK> verificationKeys = authKeyConfig.verificationKeys(keyMaterial, keyProperties);
        Map<String, Object> jwks = authKeyConfig.publicJwkSet(verificationKeys);

        assertThat(JWKSet.parse(jwks).getKeys()).extracting(JWK::getKeyID)
                .containsExactlyInAnyOrder("kid-current", "kid-retired");
        // JWKS 端点只发布公钥：任何一把都不能带私钥
        assertThat(verificationKeys).allMatch(key -> !key.isPrivate());
    }

    @Test
    void tokenSignedWithRetiredKeyStillVerifiesDuringRotationWindow() throws Exception {
        KeyPair retiredPair = generateKeyPair();
        JwtKeyMaterial keyMaterial = authKeyConfig.keyMaterial(
                privateKeyPem(generateKeyPair()), "kid-current");
        JwtKeyProperties keyProperties = new JwtKeyProperties();
        keyProperties.setRetiredPublicKeys(Map.of("kid-retired", publicKeyPem(retiredPair)));
        JwtDecoder decoder = authKeyConfig.jwtDecoder(
                authKeyConfig.verificationKeys(keyMaterial, keyProperties));

        Jwt decoded = decoder.decode(tokenSignedBy(retiredPair, "kid-retired"));

        assertThat(decoded.getSubject()).isEqualTo("teacher_1");
    }

    @Test
    void tokenSignedByUnknownKeyIsRejected() throws Exception {
        JwtKeyMaterial keyMaterial = authKeyConfig.keyMaterial(
                privateKeyPem(generateKeyPair()), "kid-current");
        JwtDecoder decoder = authKeyConfig.jwtDecoder(
                authKeyConfig.verificationKeys(keyMaterial, new JwtKeyProperties()));

        String foreignToken = tokenSignedBy(generateKeyPair(), "kid-foreign");

        assertThatThrownBy(() -> decoder.decode(foreignToken))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    private static KeyPair generateKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("RSA").generateKeyPair();
    }

    private static String privateKeyPem(KeyPair keyPair) {
        return pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    private static String publicKeyPem(KeyPair keyPair) {
        return pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getEncoder().encodeToString(der) + "\n-----END " + label + "-----";
    }

    private static String tokenSignedBy(KeyPair keyPair, String keyId) {
        RSAKey signingKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateCrtKey) keyPair.getPrivate())
                .keyID(keyId)
                .build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("auth-service")
                .subject("teacher_1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
