package io.lunar.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 创建学生账号请求体（服务间，由 contest-service 编排调用）。 */
public record CreateStudentRequest(@NotBlank String studentNo, @NotBlank String username,
                                   @NotBlank String displayName, @NotBlank String initialPassword) {}
