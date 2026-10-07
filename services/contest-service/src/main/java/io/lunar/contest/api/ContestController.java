package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.domain.AnnouncementService;
import io.lunar.contest.domain.ContestService;
import io.lunar.contest.domain.EnrollmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
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
    private final AnnouncementService announcementService;

    public ContestController(ContestService contestService, EnrollmentService enrollmentService,
                             AnnouncementService announcementService) {
        this.contestService = contestService;
        this.enrollmentService = enrollmentService;
        this.announcementService = announcementService;
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

    /** 分页列表（管理端）：按创建时间倒序，可选状态过滤。 */
    @GetMapping
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        var result = contestService.list(status, page, pageSize);
        java.util.List<ContestResponse> items = result.getRecords().stream()
                .map(ContestResponse::of).toList();
        return ResponseEntity.ok(ApiResponse.success(requestId, java.util.Map.of(
                "items", items, "total", result.getTotal(), "page", result.getCurrent(), "pageSize", result.getSize())));
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

    /** 管理员归档：FINISHED -> ARCHIVED。 */
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> archive(@PathVariable String id,
                                                                @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contestService.archive(id))));
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

    /** 发布公告（老师/管理员；docs/exam-process.md §8）。 */
    @PostMapping("/{contestId}/announcements")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AnnouncementService.AnnouncementView>> publishAnnouncement(
            @PathVariable String contestId,
            @Valid @RequestBody PublishAnnouncementRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        var view = announcementService.publish(contestId, request.content(),
                Boolean.TRUE.equals(request.important()), Boolean.TRUE.equals(request.requireConfirmation()),
                authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 公告列表：老师/管理员看全部；考生拉取即送达（require_confirmation 记录 delivered_at）。 */
    @GetMapping("/{contestId}/announcements")
    public ResponseEntity<ApiResponse<List<AnnouncementService.AnnouncementView>>> listAnnouncements(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(Object::toString).toList();
        var views = roles.contains("ROLE_CANDIDATE") && !roles.contains("ROLE_ADMIN")
                ? announcementService.listForCandidate(contestId, authentication.getName())
                : announcementService.listForOperator(contestId);
        return ResponseEntity.ok(ApiResponse.success(requestId, views));
    }

    /** 考生确认公告（幂等：保留首次确认时间）。 */
    @PostMapping("/{contestId}/announcements/{announcementId}/confirm")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<Void>> confirmAnnouncement(
            @PathVariable String contestId,
            @PathVariable String announcementId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        announcementService.confirm(contestId, announcementId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, null));
    }

    public record PublishAnnouncementRequest(@NotBlank String content,
                                             Boolean important,
                                             Boolean requireConfirmation) {}

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
