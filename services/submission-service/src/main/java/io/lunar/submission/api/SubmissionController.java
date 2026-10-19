package io.lunar.submission.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.security.Role;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.submission.api.dto.CreateSubmissionRequest;
import io.lunar.submission.domain.SubmissionService;
import io.lunar.submission.domain.dto.SubmissionPage;
import io.lunar.submission.domain.dto.SubmissionSource;
import io.lunar.submission.domain.dto.SubmissionView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提交入口（docs/architecture.md §8）：仅考生本人；幂等键走 Idempotency-Key 请求头。
 * 提交状态查询：学生看自己的，老师/管理员可查任意（监考与阅卷）。
 */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    /** 监考/阅卷权限：与 SecurityConfig 的 roles claim → ROLE_ authority 一致。 */
    private static final String ROLE_TEACHER = Role.AUTHORITY_PREFIX + Role.TEACHER.name();
    private static final String ROLE_ADMIN = Role.AUTHORITY_PREFIX + Role.ADMIN.name();

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<SubmissionView>> submit(
            @Valid @RequestBody CreateSubmissionRequest request,
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        SubmissionView view = submissionService.submit(request.contestId(), request.problemId(),
                request.language(), request.sourceCode(), idempotencyKey, requestId, authentication.getName(),
                authorization);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<ApiResponse<SubmissionPage>> list(
            @RequestParam(required = false) String contestId,
            @RequestParam(required = false) String problemId,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                submissionService.list(contestId, problemId, studentId, status, page, pageSize)));
    }

    @GetMapping("/{id}/source")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<ApiResponse<SubmissionSource>> source(
            @PathVariable String id,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, submissionService.source(id)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SubmissionView>> get(
            @PathVariable String id,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        boolean isOperator = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> ROLE_TEACHER.equals(authority) || ROLE_ADMIN.equals(authority));
        return ResponseEntity.ok(ApiResponse.success(requestId,
                submissionService.get(id, authentication.getName(), isOperator)));
    }
}
