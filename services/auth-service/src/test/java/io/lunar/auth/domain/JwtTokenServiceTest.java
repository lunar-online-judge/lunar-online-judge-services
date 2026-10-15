package io.lunar.auth.domain;

import io.lunar.auth.domain.dto.IssuedToken;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.lunar.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    @Test
    void issuesRs256TokenWithContractClaims() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate())
                .keyID("test-key")
                .build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        JwtTokenService service = new JwtTokenService(encoder, 30);

        IssuedToken token = service.issue("user_1", Role.TEACHER);

        SignedJWT signed = SignedJWT.parse(token.accessToken());
        // RS256 签名可用对应公钥验证
        assertThat(signed.verify(new RSASSAVerifier((RSAPublicKey) pair.getPublic()))).isTrue();

        JWTClaimsSet claims = signed.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("user_1");
        assertThat(claims.getIssuer()).isEqualTo("auth-service");
        assertThat(claims.getClaim("roles")).isEqualTo(List.of("TEACHER"));
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getExpirationTime().toInstant())
                .isAfter(Instant.now().plus(Duration.ofMinutes(29)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(31)));
        assertThat(token.expiresAt()).isEqualTo(claims.getExpirationTime().toInstant());
    }
}
