package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.domain.ContestService;
import io.lunar.contest.domain.EnrollmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 考试生命周期入口（docs/exam-process.md §1）：
 * 老师创建/提交审核，管理员审核发布；角色校验在路由层，资源范围校验随名单/授权功能继续细化。
 */
@RestController
@RequestMapping("/api/v1/contests")
public class ContestController {

    private final ContestService contestService;
    private final EnrollmentService enrollmentService;

    public ContestController(ContestService contestService, EnrollmentService enrollmentService) {
        this.contestService = contestService;
        this.enrollmentService = enrollmentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> create(@Valid @RequestBody CreateContestRequest request,
                                                               Authentication authentication,
                                                               @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ContestEntity entity = contestService.create(request.name(), request.description(),
                request.startAt(), request.endAt(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(entity)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ContestResponse>> get(@PathVariable String id,
                                                            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contestService.get(id))));
    }

    /** 提交管理员审核：DRAFT -> REVIEWING。 */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> review(@PathVariable String id,
                                                               @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contestService.submitForReview(id))));
    }

    /** 管理员审核通过并发布：REVIEWING -> PUBLISHED。 */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> publish(@PathVariable String id,
                                                                @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contestService.publish(id))));
    }

    /** 添加学生：账号不存在则经 auth 创建（存在则复用），并建立报名关系（docs/api.md"添加学生"）。 */
    @PostMapping("/{contestId}/students")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<EnrollmentService.CandidateView>> addStudent(
            @PathVariable String contestId,
            @Valid @RequestBody AddStudentRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        var view = enrollmentService.add(contestId, request.studentNo(), request.username(),
                request.displayName(), request.initialPassword(), authorization, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 分页查询竞赛名单；keyword 模糊匹配学号。 */
    @GetMapping("/{contestId}/students")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<EnrollmentService.CandidatePage>> listStudents(
            @PathVariable String contestId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, enrollmentService.list(contestId, page, pageSize, keyword)));
    }

    /** 重置学生密码：学生须在名单中；返回 passwordChangeRequired=true，不返回密码。 */
    @PostMapping("/{contestId}/students/{studentId}/password-reset")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetStudentPassword(
            @PathVariable String contestId,
            @PathVariable String studentId,
            @Valid @RequestBody ResetPasswordRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        enrollmentService.resetPassword(contestId, studentId, request.temporaryPassword(),
                authorization, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, Map.of("passwordChangeRequired", true)));
    }

    /** 移出名单（软删除）：学生账号与历史数据保留。 */
    @DeleteMapping("/{contestId}/students/{studentId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> removeStudent(
            @PathVariable String contestId,
            @PathVariable String studentId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        enrollmentService.remove(contestId, studentId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, null));
    }

    public record AddStudentRequest(@NotBlank String studentNo, @NotBlank String username,
                                    @NotBlank String displayName, @NotBlank String initialPassword) {}

    public record ResetPasswordRequest(@NotBlank String temporaryPassword) {}

    public record CreateContestRequest(@NotBlank String name, String description,
                                       Instant startAt, Instant endAt) {}

    public record ContestResponse(String id, String name, String description, String status,
                                  Instant startAt, Instant endAt, String createdBy,
                                  Instant createdAt, Instant updatedAt) {
        static ContestResponse of(ContestEntity e) {
            return new ContestResponse(e.getId(), e.getName(), e.getDescription(), e.getStatus(),
                    e.getStartAt(), e.getEndAt(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }
}
