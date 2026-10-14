package io.lunar.common.api;

import io.lunar.common.web.RequestIds;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把各服务的 {@link ApiException} 子类与参数校验失败统一转换为
 * {@link ApiResponse} 失败响应，code 取自 docs/api.md 错误码注册表。
 *
 * 由 WebCommonAutoConfiguration 注册（不是各服务的 @RestControllerAdvice），
 * 保证错误响应格式全平台一致；服务若要处理额外异常，另建自己的 advice 类即可。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String VALIDATION_FAILED_CODE = "VALIDATION_FAILED";
    private static final String VALIDATION_FAILED_MESSAGE = "参数校验失败";

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
}
