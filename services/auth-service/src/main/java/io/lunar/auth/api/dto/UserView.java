package io.lunar.auth.api.dto;

import io.lunar.auth.infra.UserEntity;

import java.time.Instant;

/**
 * 管理端账号视图：只暴露展示与操作需要的字段，**绝不包含密码哈希或令牌材料**。
 * 学生学号仅学生账号有值，其余角色为 null。
 */
public record UserView(String userId,
                       String username,
                       String displayName,
                       String role,
                       String status,
                       String studentNo,
                       boolean passwordChangeRequired,
                       Instant createdAt,
                       Instant updatedAt) {

    public static UserView of(UserEntity user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.getStatus(), user.getStudentNo(),
                Boolean.TRUE.equals(user.getPasswordChangeRequired()),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
