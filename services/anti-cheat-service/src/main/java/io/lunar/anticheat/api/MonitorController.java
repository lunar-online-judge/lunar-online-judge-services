package io.lunar.anticheat.api;

import io.lunar.anticheat.domain.RiskEventService;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 监考风险入口（docs/api.md"实时监考"）：
 * 上报——考生客户端自动上报（studentId = JWT sub），severity 由服务端规则表计算；
 * 查询/审核——老师与管理员（监考视图），审核动作 = ACKNOWLEDGE / CONFIRM / DISMISS。
 * 异常响应由 lunar-common 的全局异常处理器统一产出。
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
        RiskEventService.ReportedEvent view = riskEventService.report(request.contestId(), authentication.getName(),
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
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
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
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        RiskEventService.RiskEventView view = riskEventService.review(eventId, request.action(), request.note(),
                authentication.getName(), authorization);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    public record ReportEventRequest(@NotBlank String contestId, @NotBlank String eventType,
                                     String problemId, String detail, Instant occurredAt) {}

    public record ReviewRequest(@NotBlank String action, String note) {}
}
