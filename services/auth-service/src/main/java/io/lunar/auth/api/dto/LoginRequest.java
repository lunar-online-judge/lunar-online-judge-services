package io.lunar.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 登录请求体：字段名即 API 契约（docs/api.md"登录"）。 */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
