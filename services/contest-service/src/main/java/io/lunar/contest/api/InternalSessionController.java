package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.domain.ExamSessionService;
import io.lunar.contest.domain.dto.SubmissionCheck;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间接口：提交校验（供 submission-service 调用，转发学生自己的令牌）。
 * 返回该学生在指定竞赛的会话/竞赛状态与 canSubmit 判定。
 */
@RestController
@RequestMapping("/api/v1/internal/contests/{contestId}/session")
public class InternalSessionController {

    private final ExamSessionService examSessionService;

    public InternalSessionController(ExamSessionService examSessionService) {
        this.examSessionService = examSessionService;
    }

    @GetMapping
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<SubmissionCheck>> check(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                examSessionService.checkCanSubmit(contestId, authentication.getName())));
    }
}
