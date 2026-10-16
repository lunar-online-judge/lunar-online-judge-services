package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.domain.ContestAssignmentService;
import io.lunar.contest.domain.ContestProblemService;
import io.lunar.contest.infra.ContestAssignmentEntity;
import io.lunar.contest.infra.ContestProblemEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final ContestAssignmentService assignmentService;

    public InternalContestProblemController(ContestProblemService contestProblemService,
                                            ContestAssignmentService assignmentService) {
        this.contestProblemService = contestProblemService;
        this.assignmentService = assignmentService;
    }

    /**
     * 该考生在这道题上可用的版本。
     *
     * 已生成随机分配时以**分配结果**为准（"这道题分给了这位学生吗、分的是哪个版本"，
     * docs/exam-process.md §3 + BE-04）；没有分配（老考试/未配置随机）时退回考试题池，
     * 保持原有行为不变。
     */
    @GetMapping("/{problemId}")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lockedVersion(
            @PathVariable String contestId,
            @PathVariable String problemId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        if (assignmentService.hasAssignments(contestId)) {
            ContestAssignmentEntity assignment =
                    assignmentService.requireAssigned(contestId, authentication.getName(), problemId);
            return ResponseEntity.ok(ApiResponse.success(requestId,
                    Map.of("problemId", assignment.getProblemId(),
                            "problemVersionId", assignment.getProblemVersionId())));
        }
        ContestProblemEntity locked = contestProblemService.requireLocked(contestId, problemId);
        return ResponseEntity.ok(ApiResponse.success(requestId,
                Map.of("problemId", locked.getProblemId(), "problemVersionId", locked.getProblemVersionId())));
    }
}
