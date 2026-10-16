package io.lunar.submission.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.submission.api.dto.JudgmentRequest;
import io.lunar.submission.domain.SubmissionService;
import io.lunar.submission.domain.dto.SubmissionView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<ApiResponse<SubmissionView>> judged(
            @PathVariable String id,
            @Valid @RequestBody JudgmentRequest request,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        // eventId 传 null：这是补偿重放端点，没有事件信封，幂等由终态条件更新（CAS）保证；
        // 正常判决路径走 MQ 消费，那边由信封 eventId 过 consumed_events 闸门。
        SubmissionView view = submissionService.applyJudgment(id, null, request.verdict(),
                request.score(), request.passedCases(), request.totalCases(), requestId);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }
}
