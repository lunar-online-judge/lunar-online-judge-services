package io.lunar.auth.api.dto;

/** 重置密码响应：只回传"是否需强制改密"，不回传密码材料。 */
public record PasswordResetResponse(boolean passwordChangeRequired) {}
