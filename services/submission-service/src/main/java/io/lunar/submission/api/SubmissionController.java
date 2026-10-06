package io.lunar.submission.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.submission.domain.SubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 提交入口（docs/architecture.md §8）：仅考生本人；幂等键走 Idempotency-Key 请求头。
 * 提交状态查询：学生看自己的，老师/管理员可查任意（监考与阅卷）。
 */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<SubmissionService.SubmissionView>> submit(
            @Valid @RequestBody CreateSubmissionRequest request,
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        var view = submissionService.submit(request.contestId(), request.problemId(), request.language(),
                request.sourceCode(), idempotencyKey, requestId, authentication.getName(), authorization);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SubmissionService.SubmissionView>> get(
            @PathVariable String id,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        boolean operator = authentication.getAuthorities().stream()
                .anyMatch(a -> a.toString().equals("ROLE_TEACHER") || a.toString().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(ApiResponse.success(requestId,
                submissionService.get(id, authentication.getName(), operator)));
    }

    public record CreateSubmissionRequest(@NotBlank String contestId, @NotBlank String problemId,
                                          @NotBlank String language, @NotBlank String sourceCode) {}
}
