package io.lunar.common.api;

/**
 * 域异常基类：携带 docs/api.md 错误码注册表中的稳定 code 与对应 HTTP 状态，
 * 由 {@link ApiExceptionHandler} 统一转换为 {@link ApiResponse} 失败响应。
 *
 * 各服务在自己的 domain 包内继承本类定义具体异常（如 AuthException、ContestException），
 * 错误码必须先登记进 api.md 注册表再使用。
 */
public abstract class ApiException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    protected ApiException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** 错误码注册表中的稳定标识（大写下划线命名，如 CONTEST_NOT_ENROLLED）。 */
    public String code() {
        return code;
    }

    /** 该错误对应的 HTTP 状态码。 */
    public int httpStatus() {
        return httpStatus;
    }
}
