package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.domain.ContestProblemService;
import io.lunar.contest.infra.ContestProblemEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务间接口（BE-04）：提交时查"这场考试给这道题锁定的版本"。
 * 由 submission 在考生上下文里调用（与内部会话校验同一约定），不经网关。
 * 题目不在考试里时返回 404 `CONTEST_PROBLEM_NOT_IN_CONTEST`。
 */
@RestController
@RequestMapping("/api/v1/internal/contests/{contestId}/problems")
public class InternalContestProblemController {

    private final ContestProblemService contestProblemService;

    public InternalContestProblemController(ContestProblemService contestProblemService) {
        this.contestProblemService = contestProblemService;
    }

    @GetMapping("/{problemId}")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lockedVersion(
            @PathVariable String contestId,
            @PathVariable String problemId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ContestProblemEntity locked = contestProblemService.requireLocked(contestId, problemId);
        return ResponseEntity.ok(ApiResponse.success(requestId,
                Map.of("problemId", locked.getProblemId(), "problemVersionId", locked.getProblemVersionId())));
    }
}
