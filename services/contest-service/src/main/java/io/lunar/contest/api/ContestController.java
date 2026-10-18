package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.security.AccessScope;
import io.lunar.common.security.Role;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.api.dto.AddStudentRequest;
import io.lunar.contest.api.dto.ContestListResponse;
import io.lunar.contest.api.dto.ContestResponse;
import io.lunar.contest.api.dto.CreateContestRequest;
import io.lunar.contest.api.dto.PasswordResetResponse;
import io.lunar.contest.api.dto.PublishAnnouncementRequest;
import io.lunar.contest.api.dto.ResetPasswordRequest;
import io.lunar.contest.domain.AnnouncementService;
import io.lunar.contest.domain.ContestException;
import io.lunar.contest.domain.ContestAssignmentService;
import io.lunar.contest.domain.ContestService;
import io.lunar.contest.domain.EnrollmentService;
import io.lunar.contest.domain.ExamSessionService;
import io.lunar.contest.domain.dto.AnnouncementView;
import io.lunar.contest.domain.dto.CandidatePage;
import io.lunar.contest.domain.dto.ContestPage;
import io.lunar.contest.domain.dto.EnrollmentResult;
import io.lunar.contest.domain.dto.RosterImportResult;
import io.lunar.contest.domain.dto.SessionView;
import io.lunar.contest.infra.ContestEntity;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 考试生命周期入口（docs/exam-process.md §1）。
 *
 * 授权分两层（BACKEND_IMPROVEMENT_PLAN BE-01）：路由层 `@PreAuthorize` 管角色，
 * 资源层用 {@link AccessScope} 管"这一场考试归不归我"——管理端每个入口都先做范围校验，
 * 越权与不存在同样返回 404（不泄露资源是否存在）；考生侧入口只按"是否已报名"放行。
 */
@RestController
@RequestMapping("/api/v1/contests")
public class ContestController {

    private final ContestService contestService;
    private final EnrollmentService enrollmentService;
    private final AnnouncementService announcementService;
    private final ExamSessionService examSessionService;
    private final ContestAssignmentService assignmentService;

    public ContestController(ContestService contestService, EnrollmentService enrollmentService,
                             AnnouncementService announcementService, ExamSessionService examSessionService,
                             ContestAssignmentService assignmentService) {
        this.contestService = contestService;
        this.enrollmentService = enrollmentService;
        this.announcementService = announcementService;
        this.examSessionService = examSessionService;
        this.assignmentService = assignmentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> create(
            @Valid @RequestBody CreateContestRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ContestEntity contest = contestService.create(request.name(), request.description(),
                request.startAt(), request.endAt(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contest)));
    }

    /** 分页列表（管理端）：先叠加服务端范围（老师只看自己的），再套用户筛选，total 同样在范围内。 */
    @GetMapping
    public ResponseEntity<ApiResponse<ContestListResponse>> list(
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestParam(required = false) String status,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ContestPage result = contestService.list(status, page, pageSize, AccessScope.of(authentication));
        List<ContestResponse> items = result.items().stream().map(ContestResponse::of).toList();
        return ResponseEntity.ok(ApiResponse.success(requestId, new ContestListResponse(
                items, result.total(), result.page(), result.pageSize())));
    }

    /** 详情：老师/管理员限自己范围内；考生限已报名的考试；其余按不存在处理。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ContestResponse>> get(
            @PathVariable String id,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                ContestResponse.of(readableContest(id, authentication))));
    }

    /** 提交管理员审核：DRAFT -> REVIEWING。 */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> review(
            @PathVariable String id,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                ContestResponse.of(contestService.submitForReview(ownedContestId(id, authentication)))));
    }

    /** 管理员审核通过并发布：REVIEWING -> PUBLISHED。 */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> publish(
            @PathVariable String id,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ContestResponse response = ContestResponse.of(contestService.publish(id));
        // 发布时生成随机题目分配（docs/exam-process.md §3）：题池为空时服务内部只记 warn，不影响发布
        assignmentService.generate(id, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, response));
    }

    /** 管理员归档：FINISHED -> ARCHIVED。 */
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> archive(
            @PathVariable String id,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contestService.archive(id))));
    }

    /** 添加学生：账号不存在则经 auth 创建（存在则复用），并建立报名关系（docs/api.md"添加学生"）。 */
    @PostMapping("/{contestId}/students")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<EnrollmentResult>> addStudent(
            @PathVariable String contestId,
            @Valid @RequestBody AddStudentRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        EnrollmentResult result = enrollmentService.add(ownedContestId(contestId, authentication), request.studentNo(),
                request.username(), request.displayName(), request.initialPassword(),
                authorization, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, result));
    }

    /** 分页查询竞赛名单；keyword 模糊匹配学号。 */
    @GetMapping("/{contestId}/students")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CandidatePage>> listStudents(
            @PathVariable String contestId,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestParam(required = false) String keyword,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                enrollmentService.list(ownedContestId(contestId, authentication), page, pageSize, keyword)));
    }

    /**
     * CSV 批量导入名单（docs/exam-process.md §9）：multipart 上传，列顺序
     * studentNo,username,displayName,initialPassword，可选表头；逐行等价于一次"添加学生"，
     * 逐行返回成功或失败原因，部分行失败不回滚已成功行。
     */
    @PostMapping(value = "/{contestId}/students/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<RosterImportResult>> importStudents(
            @PathVariable String contestId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) throws IOException {
        String csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        RosterImportResult result = enrollmentService.importRoster(ownedContestId(contestId, authentication), csvContent,
                authorization, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, result));
    }

    /** 重置学生密码：学生须在名单中；返回 passwordChangeRequired=true，不返回密码。 */
    @PostMapping("/{contestId}/students/{studentId}/password-reset")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<PasswordResetResponse>> resetStudentPassword(
            @PathVariable String contestId,
            @PathVariable String studentId,
            @Valid @RequestBody ResetPasswordRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        enrollmentService.resetPassword(ownedContestId(contestId, authentication), studentId,
                request.temporaryPassword(), authorization, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, new PasswordResetResponse(true)));
    }

    /** 移出名单（软删除）：学生账号与历史数据保留；当前考试会话同时失效（exam-process §9）。 */
    @DeleteMapping("/{contestId}/students/{studentId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> removeStudent(
            @PathVariable String contestId,
            @PathVariable String studentId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        enrollmentService.remove(ownedContestId(contestId, authentication), studentId,
                authorization, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, null));
    }

    /** 终止考生会话（管理端；TERMINATED 为终态，考生重进被拒）。 */
    @PostMapping("/{contestId}/students/{studentId}/session/terminate")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<SessionView>> terminateSession(
            @PathVariable String contestId,
            @PathVariable String studentId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                examSessionService.terminate(ownedContestId(contestId, authentication), studentId)));
    }

    /** 发布公告（老师/管理员；docs/exam-process.md §8）。 */
    @PostMapping("/{contestId}/announcements")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AnnouncementView>> publishAnnouncement(
            @PathVariable String contestId,
            @Valid @RequestBody PublishAnnouncementRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        AnnouncementView view = announcementService.publish(ownedContestId(contestId, authentication), request.content(),
                Boolean.TRUE.equals(request.important()), Boolean.TRUE.equals(request.requireConfirmation()),
                authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 公告列表：老师/管理员看全部（限自己范围内）；考生拉取即送达（require_confirmation 记录 delivered_at）。 */
    @GetMapping("/{contestId}/announcements")
    public ResponseEntity<ApiResponse<List<AnnouncementView>>> listAnnouncements(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        List<AnnouncementView> views = isCandidateOnly(authentication)
                ? announcementService.listForCandidate(requireEnrolled(contestId, authentication), authentication.getName())
                : announcementService.listForOperator(ownedContestId(contestId, authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, views));
    }

    /**
     * 公告实时推送（SSE）：新公告发布后立即推给该场考试的教师/管理员与已报名考生。
     * 权限与列表接口一致：管理侧限考试归属，考生限已报名。
     */
    @GetMapping(value = "/{contestId}/announcements/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN', 'CANDIDATE')")
    public SseEmitter streamAnnouncements(
            @PathVariable String contestId,
            Authentication authentication) {
        if (isCandidateOnly(authentication)) {
            requireEnrolled(contestId, authentication);
        } else {
            contestService.getOwned(contestId, AccessScope.of(authentication));
        }
        return announcementService.subscribe(contestId);
    }

    /** 考生确认公告（幂等：保留首次确认时间）。 */
    @PostMapping("/{contestId}/announcements/{announcementId}/confirm")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<Void>> confirmAnnouncement(
            @PathVariable String contestId,
            @PathVariable String announcementId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        announcementService.confirm(requireEnrolled(contestId, authentication), announcementId,
                authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, null));
    }

    /**
     * 管理端资源范围校验（BE-01）：不在范围内与不存在同样抛 404，返回校验通过的 ID。
     * 直接以 ID 调用 detail/名单/重置等入口无法绕过——每个入口都过这一关。
     */
    private String ownedContestId(String contestId, Authentication authentication) {
        contestService.getOwned(contestId, AccessScope.of(authentication));
        return contestId;
    }

    /** 考生侧资源范围：必须已报名该考试，否则按不存在处理。 */
    private String requireEnrolled(String contestId, Authentication authentication) {
        if (!enrollmentService.isEnrolled(contestId, authentication.getName())) {
            throw new ContestException.NotFound();
        }
        return contestId;
    }

    /** 详情读取：老师/管理员走归属范围，考生走报名范围，其余一律不存在。 */
    private ContestEntity readableContest(String id, Authentication authentication) {
        if (isCandidateOnly(authentication)) {
            requireEnrolled(id, authentication);
            return contestService.get(id);
        }
        return contestService.getOwned(id, AccessScope.of(authentication));
    }

    /** 是否"纯考生"（有 CANDIDATE 且没有 TEACHER/ADMIN）：决定走考生侧还是管理侧范围。 */
    private static boolean isCandidateOnly(Authentication authentication) {
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
        return authorities.contains(Role.AUTHORITY_PREFIX + Role.CANDIDATE.name())
                && !authorities.contains(Role.AUTHORITY_PREFIX + Role.ADMIN.name())
                && !authorities.contains(Role.AUTHORITY_PREFIX + Role.TEACHER.name());
    }
}
