package io.lunar.contest.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 添加学生请求体：账号不存在则经 auth-service 创建，存在则复用。 */
public record AddStudentRequest(@NotBlank String studentNo, @NotBlank String username,
                                @NotBlank String displayName, @NotBlank String initialPassword) {}
