package io.lunar.auth.domain.dto;

import io.lunar.common.security.Role;

import java.time.Instant;

/** 登录结果：tokenType 由 api 层固定为 Bearer，不在此承载。 */
public record LoginResult(String userId, String accessToken, Instant expiresAt,
                          Role role, boolean passwordChangeRequired) {}
