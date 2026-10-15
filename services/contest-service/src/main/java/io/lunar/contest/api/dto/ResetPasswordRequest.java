package io.lunar.contest.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 重置学生密码请求体：密码材料只进不出，响应不回传。 */
public record ResetPasswordRequest(@NotBlank String temporaryPassword) {}
