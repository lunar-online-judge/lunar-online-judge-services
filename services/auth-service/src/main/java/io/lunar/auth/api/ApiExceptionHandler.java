package io.lunar.auth.api;

import io.lunar.auth.domain.AuthException;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 域异常 → 稳定错误码 + HTTP 状态（api.md 错误码注册表）。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> authException(AuthException e) {
        return ResponseEntity.status(e.httpStatus()).body(ApiResponse.failure(currentRequestId(), e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(currentRequestId(), "VALIDATION_FAILED", "参数校验失败"));
    }

    private static String currentRequestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getHeader(RequestIdFilter.HEADER);
        }
        return null;
    }
}
