package io.lunar.anticheat.api;

import io.lunar.anticheat.api.dto.ReportEventRequest;
import io.lunar.anticheat.api.dto.ReviewRequest;
import io.lunar.anticheat.domain.RiskEventService;
import io.lunar.anticheat.domain.dto.ReportedEvent;
import io.lunar.anticheat.domain.dto.ReviewPage;
import io.lunar.anticheat.domain.dto.RiskEventView;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
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
    public ResponseEntity<ApiResponse<ReportedEvent>> report(
            @Valid @RequestBody ReportEventRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ReportedEvent view = riskEventService.report(request.contestId(), authentication.getName(),
                request.eventType(), request.detail(), request.occurredAt(), request.problemId(), requestId);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /**
     * 事件分页（管理端）：**仅 ADMIN**。BE-01 要求按资源范围过滤，而风险事件只知道 contestId、
     * 不知道竞赛归属；跨服务的范围查询落地前先收紧为管理员专用（BACKEND_IMPROVEMENT_PLAN 的兼容说明）。
     */
    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReviewPage>> list(
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

    /** 审核风险事件（终态不可再动）：同上，先收紧为管理员专用，待范围查询落地再放开给被分配的监考。 */
    @PostMapping("/events/{eventId}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RiskEventView>> review(
            @PathVariable String eventId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        RiskEventView view = riskEventService.review(eventId, request.action(), request.note(),
                authentication.getName(), authorization);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }
}
