package io.lunar.auth.api.dto;

/** 用户名称轻量视图：只暴露 ID、账号与显示名，供列表把 ID 渲染成人名。 */
public record UserNameView(String userId, String username, String displayName) {
}
