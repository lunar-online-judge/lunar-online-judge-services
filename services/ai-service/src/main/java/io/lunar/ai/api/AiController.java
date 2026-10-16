package io.lunar.ai.api;

import io.lunar.ai.api.dto.CodeReviewJobRequest;
import io.lunar.ai.api.dto.ProblemDraftJobRequest;
import io.lunar.ai.config.AiLimitsProperties;
import io.lunar.ai.config.AiModelFactory;
import io.lunar.ai.config.AiProviderProperties;
import io.lunar.ai.domain.AiException;
import io.lunar.ai.domain.AiJobService;
import io.lunar.ai.domain.AiJobType;
import io.lunar.ai.domain.AiScopeGuard;
import io.lunar.ai.domain.ProblemDraftService;
import io.lunar.ai.domain.dto.AiFindingView;
import io.lunar.ai.domain.dto.AiJobRequest;
import io.lunar.ai.domain.dto.AiJobView;
import io.lunar.ai.domain.dto.AiPage;
import io.lunar.ai.domain.dto.AiProviderView;
import io.lunar.ai.domain.dto.ProblemDraftSpec;
import io.lunar.ai.domain.dto.ProblemDraftView;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.security.AccessScope;
import io.lunar.common.security.Role;
import io.lunar.common.web.RequestIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * AI 能力入口（docs/api.md"AI"节）。
 *
 * 三层权限，缺一不可：
 *   ① 角色：{@code @PreAuthorize} 拦掉考生（AI 是教师/管理员的工具，考生不该触发计费调用）；
 *   ② 资源范围：{@link AiScopeGuard} 按考试归属判断（越权与不存在同样 404）；
 *   ③ 身份来源：actor/role 只从**已验证令牌**取，绝不读请求体（BE-01）。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiJobService jobService;
    private final AiScopeGuard scopeGuard;
    private final ProblemDraftService problemDrafts;
    private final AiProviderProperties providerProperties;
    private final AiLimitsProperties limitsProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiController(AiJobService jobService, AiScopeGuard scopeGuard, ProblemDraftService problemDrafts,
                        AiProviderProperties providerProperties, AiLimitsProperties limitsProperties) {
        this.jobService = jobService;
        this.scopeGuard = scopeGuard;
        this.problemDrafts = problemDrafts;
        this.providerProperties = providerProperties;
        this.limitsProperties = limitsProperties;
    }

    /** 请求代码评审：立即返回作业号，评审在后台执行（LLM 调用是秒级到分钟级的慢操作）。 */
    @PostMapping("/jobs/code-review")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AiJobView>> requestCodeReview(
            @Valid @RequestBody CodeReviewJobRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        AccessScope scope = AccessScope.of(authentication);
        scopeGuard.requireContestAccess(request.contestId(), scope);
        AiJobRequest jobRequest = new AiJobRequest(AiJobType.CODE_REVIEW, request.contestId(), null,
                request.submissionId(), null, null, authentication.getName(), roleOf(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, jobService.enqueue(jobRequest, requestId)));
    }

    /** 请求出题草稿：产物是**草稿**，教师确认后才由 problem 的既有接口落库。 */
    @PostMapping("/jobs/problem-draft")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AiJobView>> requestProblemDraft(
            @Valid @RequestBody ProblemDraftJobRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        requireAccessTo(request.contestId(), AccessScope.of(authentication));
        AiJobRequest jobRequest = new AiJobRequest(AiJobType.PROBLEM_DRAFT, request.contestId(), null, null, null,
                specJson(request), authentication.getName(), roleOf(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, jobService.enqueue(jobRequest, requestId)));
    }

    /** 出题草稿列表：与作业/结论同一套范围规则（不给 contestId 时仅 ADMIN）。 */
    @GetMapping("/drafts")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AiPage<ProblemDraftView>>> listDrafts(
            @RequestParam(required = false) String contestId,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        requireAccessTo(contestId, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, problemDrafts.list(contestId, page, pageSize)));
    }

    @GetMapping("/drafts/{draftId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ProblemDraftView>> getDraft(
            @PathVariable String draftId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ProblemDraftView draft = problemDrafts.view(draftId);
        requireAccessTo(draft.contestId(), AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, draft));
    }

    /** 出题需求落 input_payload：作业可能几小时后才执行，必须把当初的输入原样存下来。 */
    private String specJson(ProblemDraftJobRequest request) {
        try {
            return objectMapper.writeValueAsString(
                    new ProblemDraftSpec(request.requirement(), request.difficulty(), request.languageHint()));
        } catch (JsonProcessingException exception) {
            throw new AiException.InvalidRequest("出题需求无法序列化");
        }
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AiJobView>> getJob(
            @PathVariable String jobId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        AiJobView job = jobService.get(jobId);
        requireAccessTo(job.contestId(), AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, job));
    }

    /** 作业列表：不给 contestId 时只有 ADMIN 能看（避免老师枚举到别人考试的任务）。 */
    @GetMapping("/jobs")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AiPage<AiJobView>>> listJobs(
            @RequestParam(required = false) String contestId,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        requireAccessTo(contestId, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId,
                jobService.listJobs(contestId, jobType, status, page, pageSize)));
    }

    /** 结论列表：同上，不给 contestId 时只有 ADMIN。 */
    @GetMapping("/findings")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AiPage<AiFindingView>>> listFindings(
            @RequestParam(required = false) String contestId,
            @RequestParam(required = false) String submissionId,
            @RequestParam(required = false) String findingType,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        requireAccessTo(contestId, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId,
                jobService.listFindings(contestId, submissionId, findingType, page, pageSize)));
    }

    @GetMapping("/findings/{findingId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AiFindingView>> getFinding(
            @PathVariable String findingId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        AiFindingView finding = jobService.getFinding(findingId);
        requireAccessTo(finding.contestId(), AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, finding));
    }

    /**
     * Provider 运行态（仅 ADMIN）：运维要能一眼确认"数据发去哪、用的哪个模型"。
     * 这正是"把学生代码发出去之前必须能核对"的那条信息，因此不给 api-key 回显。
     */
    @GetMapping("/provider")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AiProviderView>> provider(
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        AiProviderView view = new AiProviderView(providerProperties.getMode().name(),
                AiModelFactory.hostOf(providerProperties.getBaseUrl()), providerProperties.getChatModel());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 有 contestId 就按考试归属判；没有则只有 ADMIN（无归属资源对老师不可见，与 BE-01 一致）。 */
    private void requireAccessTo(String contestId, AccessScope scope) {
        if (contestId == null || contestId.isBlank()) {
            scopeGuard.requireGlobalAccess(scope);
            return;
        }
        scopeGuard.requireContestAccess(contestId, scope);
    }

    /** 角色快照：只从已验证令牌的 authorities 取（不取请求参数，BE-01）。 */
    private static String roleOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.replace(Role.AUTHORITY_PREFIX, ""))
                .findFirst().orElse(null);
    }
}
