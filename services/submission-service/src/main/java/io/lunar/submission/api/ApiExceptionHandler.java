package io.lunar.submission.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.submission.domain.SubmissionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 域异常 → 稳定错误码 + HTTP 状态（api.md 错误码注册表）。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SubmissionException.class)
    public ResponseEntity<ApiResponse<Void>> submissionException(SubmissionException e) {
        return ResponseEntity.status(e.httpStatus())
                .body(ApiResponse.failure(currentRequestId(), e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(currentRequestId(), "VALIDATION_FAILED", "参数校验失败"));
    }

    private static String currentRequestId() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader(RequestIdFilter.HEADER);
        }
        return null;
    }
}
