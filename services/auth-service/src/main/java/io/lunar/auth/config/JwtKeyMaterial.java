package io.lunar.auth.config;

import com.nimbusds.jose.jwk.RSAKey;

import java.security.interfaces.RSAPublicKey;

/**
 * 当前签发密钥（含私钥）：签发与 JWKS 分发的唯一来源。
 * 独立成文件（不再内嵌在 {@link AuthKeyConfig} 内），与仓库"一个类型一个文件"的约定一致。
 */
public record JwtKeyMaterial(RSAKey rsaKey, RSAPublicKey publicKey) {
}
