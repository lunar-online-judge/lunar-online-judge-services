package io.lunar.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建学生账号请求体（服务间，由 contest-service 编排调用）。 */
public record CreateStudentRequest(@NotBlank String studentNo, @NotBlank String username,
                                   @NotBlank String displayName,
                                   @Size(max = 100) String className,
                                   @NotBlank String initialPassword) {}
