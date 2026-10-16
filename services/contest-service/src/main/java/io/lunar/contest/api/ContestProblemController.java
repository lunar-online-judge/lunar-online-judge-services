package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.security.AccessScope;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.api.dto.LockProblemRequest;
import io.lunar.contest.domain.ContestProblemService;
import io.lunar.contest.infra.ContestProblemEntity;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 考试题目清单（docs/exam-process.md §3、BACKEND_IMPROVEMENT_PLAN BE-04）：
 * 加入题目时**锁定版本**，之后全局题目发布不影响这场考试的题面与测试数据。
 * 限考试归属者或 ADMIN（BE-01 范围校验在服务层）。
 */
@RestController
@RequestMapping("/api/v1/contests/{contestId}/problems")
public class ContestProblemController {

    private final ContestProblemService contestProblemService;

    public ContestProblemController(ContestProblemService contestProblemService) {
        this.contestProblemService = contestProblemService;
    }

    /** 加入题目并锁定版本；重复加入同一题目视为改锁定版本。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ContestProblemEntity>> lock(
            @PathVariable String contestId,
            @Valid @RequestBody LockProblemRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, contestProblemService.lock(
                contestId, request.problemId(), request.problemVersionId(), AccessScope.of(authentication))));
    }

    /** 考试题目清单（含锁定版本）。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ContestProblemEntity>>> list(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                contestProblemService.list(contestId, AccessScope.of(authentication))));
    }

    /** 移出题目。 */
    @DeleteMapping("/{problemId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> unlock(
            @PathVariable String contestId,
            @PathVariable String problemId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        contestProblemService.unlock(contestId, problemId, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, null));
    }
}
