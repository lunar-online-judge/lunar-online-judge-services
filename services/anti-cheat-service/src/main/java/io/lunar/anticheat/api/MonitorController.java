package io.lunar.anticheat.api;

import io.lunar.anticheat.domain.RiskEventService;
import io.lunar.anticheat.domain.ReviewException;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 监考风险入口（docs/api.md"实时监考"）：
 * 上报——考生客户端自动上报（studentId = JWT sub），severity 由服务端规则表计算；
 * 查询/审核——老师与管理员（监考视图），审核动作 = ACKNOWLEDGE / CONFIRM / DISMISS。
 */
@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {

    private final RiskEventService riskEventService;

    public MonitorController(RiskEventService riskEventService) {
        this.riskEventService = riskEventService;
    }

    @PostMapping("/events")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<RiskEventService.ReportedEvent>> report(
            @Valid @RequestBody ReportEventRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        var view = riskEventService.report(request.contestId(), authentication.getName(),
                request.eventType(), request.detail(), request.occurredAt(), request.problemId(), requestId);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    @GetMapping("/events")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<RiskEventService.ReviewPage>> list(
            @RequestParam(required = false) String contestId,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                riskEventService.list(contestId, studentId, severity, reviewStatus, page, pageSize)));
    }

    @PostMapping("/events/{eventId}/review")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<RiskEventService.RiskEventView>> review(
            @PathVariable String eventId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        var view = riskEventService.review(eventId, request.action(), request.note(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    public record ReportEventRequest(@NotBlank String contestId, @NotBlank String eventType,
                                     String problemId, String detail, Instant occurredAt) {}

    public record ReviewRequest(@NotBlank String action, String note) {}

    @RestControllerAdvice
    public static class Handler {
        @ExceptionHandler(ReviewException.class)
        public ResponseEntity<ApiResponse<Void>> reviewException(ReviewException e) {
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
}
