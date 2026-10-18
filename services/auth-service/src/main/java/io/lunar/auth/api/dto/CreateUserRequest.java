package io.lunar.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端创建账号请求：管理员可创建管理员（子管理员）、教师与学生。
 *
 * <p>角色的取值由 {@link Pattern} 收敛到平台现有角色枚举，避免把非法角色写进库；
 * 学生学号是否必填取决于角色，这类跨字段约束在服务层判定。</p>
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[A-Za-z0-9_.-]{3,100}", message = "登录账号只能包含字母、数字、下划线、点和短横线（3-100 位）")
        String username,
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Pattern(regexp = "ADMIN|TEACHER|CANDIDATE", message = "角色只能是 ADMIN、TEACHER 或 CANDIDATE")
        String role,
        @Size(max = 64) String studentNo,
        @NotBlank @Size(min = 8, max = 64) String initialPassword) {
}
