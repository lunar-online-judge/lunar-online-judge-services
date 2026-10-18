package io.lunar.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 管理端启用/禁用账号请求；禁用时同时失效该账号已签发的令牌。 */
public record UpdateUserStatusRequest(
        @NotBlank @Pattern(regexp = "ACTIVE|DISABLED", message = "状态只能是 ACTIVE 或 DISABLED")
        String status) {
}
