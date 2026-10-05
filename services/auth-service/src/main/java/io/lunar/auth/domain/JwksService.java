package io.lunar.auth.domain;

import org.springframework.stereotype.Component;

import java.util.Map;

/** JWKS 端点的内容提供者：返回仅含公钥的 JWKSet JSON。 */
@Component
public class JwksService {

    private final Map<String, Object> jwks;

    public JwksService(Map<String, Object> publicJwkSet) {
        this.jwks = publicJwkSet;
    }

    public Map<String, Object> asJson() {
        return jwks;
    }
}
