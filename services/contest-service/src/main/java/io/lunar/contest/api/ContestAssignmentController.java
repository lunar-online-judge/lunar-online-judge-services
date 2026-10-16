package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.security.AccessScope;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.domain.ContestAssignmentService;
import io.lunar.contest.infra.ContestAssignmentEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 随机题目分配（docs/exam-process.md §3）。
 *
 * 管理端（老师/管理员，限考试归属者）：查看某学生的分配、显式重算分配；
 * 考生端：只能看自己的分配（studentId 取自令牌，不接受请求参数，BE-01 的一贯做法）。
 */
@RestController
@RequestMapping("/api/v1/contests/{contestId}/assignments")
public class ContestAssignmentController {

    private final ContestAssignmentService assignmentService;

    public ContestAssignmentController(ContestAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /** 考生看自己的题目分配（进入考试后使用已生成的题目集合）。 */
    @GetMapping("/me")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<List<ContestAssignmentEntity>>> mine(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                assignmentService.listForStudent(contestId, authentication.getName())));
    }

    /** 老师/管理员查看某学生的分配（考试后复盘"他当时看到哪些题"）。 */
    @GetMapping("/{studentId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ContestAssignmentEntity>>> ofStudent(
            @PathVariable String contestId,
            @PathVariable String studentId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                assignmentService.listForStudent(contestId, studentId, AccessScope.of(authentication))));
    }

    /** 显式重算分配（幂等：已有分配的学生跳过）。返回本次新增的分配条数。 */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generate(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        int created = assignmentService.generate(contestId, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, Map.of("created", created)));
    }
}
