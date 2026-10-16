package io.lunar.common.identity;

import java.time.Instant;

/** 服务令牌响应：role 固定为 SERVICE（{@code roles=["SERVICE"]}），expiresAt 与令牌 exp 声明一致。 */
public record ServiceTokenResponse(String accessToken, String tokenType, Instant expiresAt, String role) {
}
