package io.lunar.auth.api.dto;

import java.util.List;

/** 管理端账号分页响应：page 从 1 起，pageSize 上限 100。 */
public record UserPage(List<UserView> items, long total, long page, long pageSize) {
}
