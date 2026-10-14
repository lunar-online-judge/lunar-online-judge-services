package io.lunar.auth.domain;

import io.lunar.common.api.ApiException;

/**
 * 认证域异常基类：携带 api.md 错误码注册表中的稳定 code 与对应 HTTP 状态，
 * 由 lunar-common 的全局异常处理器统一转换为 ApiResponse.failure。
 */
public class AuthException extends ApiException {

    private AuthException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }

    /** 401：用户不存在与密码错误返回同一码，防账号枚举。 */
    public static final class InvalidCredentials extends AuthException {
        public InvalidCredentials() {
            super("AUTH_INVALID_CREDENTIALS", 401, "用户名或密码错误");
        }
    }

    /** 403：密码正确但账号被禁用。 */
    public static final class AccountDisabled extends AuthException {
        public AccountDisabled() {
            super("AUTH_ACCOUNT_DISABLED", 403, "账号已禁用");
        }
    }

    /** 429：连续失败触发锁定。 */
    public static final class RateLimited extends AuthException {
        public RateLimited() {
            super("AUTH_RATE_LIMITED", 429, "登录失败次数过多，请稍后再试");
        }
    }
}
