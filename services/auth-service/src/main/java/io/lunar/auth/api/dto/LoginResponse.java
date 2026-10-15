package io.lunar.auth.api.dto;

import io.lunar.common.security.Role;

import java.time.Instant;

/** 登录响应体：tokenType 固定为 Bearer，expiresAt 为访问令牌到期时刻。 */
public record LoginResponse(String userId, String accessToken, String tokenType, Instant expiresAt,
                            Role role, boolean passwordChangeRequired) {}
