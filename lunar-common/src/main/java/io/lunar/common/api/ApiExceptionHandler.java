package io.lunar.common.api;

import io.lunar.common.web.RequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把各服务的 {@link ApiException} 子类、参数校验失败、请求体不可解析统一转换为
 * {@link ApiResponse} 失败响应，code 取自 docs/api.md 错误码注册表。
 *
 * 由 WebCommonAutoConfiguration 注册（不是各服务的 @RestControllerAdvice），
 * 保证错误响应格式全平台一致；服务若要处理额外异常，另建自己的 advice 类即可。
 *
 * 兜底：未分类异常统一转 500 INTERNAL_ERROR——异常细节只进日志，不进响应体，
 * 这样"任何响应都是 ApiResponse 信封、5xx 也带 requestId"这条契约才成立。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String VALIDATION_FAILED_CODE = "VALIDATION_FAILED";
    private static final String VALIDATION_FAILED_MESSAGE = "参数校验失败";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private static final String INTERNAL_ERROR_MESSAGE = "服务内部错误";

    /**
     * Spring Security 异常所在的包前缀。本模块不依赖 spring-security，只能用类名判定：
     * 认证/授权异常必须交回过滤器链（保持 401/403），不能被下面的兜底吞成 500。
     */
    private static final String SECURITY_EXCEPTION_PACKAGE_PREFIX = "org.springframework.security.";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomainException(ApiException exception) {
        return ResponseEntity.status(exception.httpStatus())
                .body(ApiResponse.failure(RequestIds.currentRequestId(), exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationFailure(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                RequestIds.currentRequestId(), VALIDATION_FAILED_CODE, VALIDATION_FAILED_MESSAGE));
    }

    /** 请求体缺失或 JSON 不可解析属于入参问题：按 400 返回，而不是掉进 500 兜底。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                RequestIds.currentRequestId(), VALIDATION_FAILED_CODE, VALIDATION_FAILED_MESSAGE));
    }

    /**
     * 兜底：未分类异常 → 500 INTERNAL_ERROR。
     *
     * 两类异常必须原样抛出、不能在这里包装：框架自带且已携带状态语义的异常（{@link ErrorResponse}，
     * 如 404/405/415）与 Spring Security 的认证/授权异常——它们由容器或过滤器链产出正确的状态码。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) throws Exception {
        if (exception instanceof ErrorResponse || isSecurityException(exception)) {
            throw exception;
        }
        log.error("未分类异常，已按 {} 返回", INTERNAL_ERROR_CODE, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(
                RequestIds.currentRequestId(), INTERNAL_ERROR_CODE, INTERNAL_ERROR_MESSAGE));
    }

    private static boolean isSecurityException(Exception exception) {
        return exception.getClass().getName().startsWith(SECURITY_EXCEPTION_PACKAGE_PREFIX);
    }
}
