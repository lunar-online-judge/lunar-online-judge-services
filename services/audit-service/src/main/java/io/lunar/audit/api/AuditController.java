package io.lunar.audit.api;

import io.lunar.audit.domain.AuditService;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 审计入口：
 * - 写入（服务间）：`POST /api/v1/internal/audit/records`——各服务转发操作者令牌调用（尽力而为，不阻断主流程）；
 * - 查询（管理端）：`GET /api/v1/audit/records`——老师/管理员；账本无更新/删除接口（追加式）。
 */
@RestController
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @PostMapping("/api/v1/internal/audit/records")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AuditService.AuditRecordView>> record(
            @Valid @RequestBody RecordRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        String actorRole = authentication.getAuthorities().stream()
                .map(a -> a.toString().replace("ROLE_", ""))
                .findFirst().orElse(null);
        var view = auditService.record(request.action(), authentication.getName(), actorRole,
                request.targetType(), request.targetId(), request.contestId(),
                request.detail(), requestId);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    @GetMapping("/api/v1/audit/records")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AuditService.AuditPage>> query(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String contestId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                auditService.query(action, actorId, contestId, page, pageSize)));
    }

    public record RecordRequest(@NotBlank String action, String targetType, String targetId,
                                String contestId, String detail) {}

    @RestControllerAdvice
    public static class Handler {
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
}
