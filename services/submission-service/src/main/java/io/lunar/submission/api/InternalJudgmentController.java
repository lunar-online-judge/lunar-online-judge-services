package io.lunar.submission.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.submission.domain.SubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 判题结果回写（服务间，过渡实现）：当前由 judge-service / 管理员调用；
 * RocketMQ 接入后改为消费 lunar-submission-judged，本端点保留给补偿重放。
 */
@RestController
@RequestMapping("/api/v1/internal/submissions")
public class InternalJudgmentController {

    private final SubmissionService submissionService;

    public InternalJudgmentController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping("/{id}/judged")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SubmissionService.SubmissionView>> judged(
            @PathVariable String id,
            @Valid @RequestBody JudgmentRequest request,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        var view = submissionService.applyJudgment(id, request.verdict(), request.score(),
                request.passedCases(), request.totalCases(), requestId);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    public record JudgmentRequest(@NotBlank String verdict, Integer score,
                                  Integer passedCases, Integer totalCases) {}
}
