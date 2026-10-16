package io.lunar.problem.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.security.AccessScope;
import org.springframework.security.core.GrantedAuthority;
import io.lunar.common.security.Role;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.problem.api.dto.CreateProblemRequest;
import io.lunar.problem.api.dto.CreateVersionRequest;
import io.lunar.problem.domain.ProblemService;
import io.lunar.problem.domain.dto.ProblemPage;
import io.lunar.problem.domain.dto.ProblemView;
import io.lunar.problem.domain.dto.TestcaseView;
import io.lunar.problem.domain.dto.VersionView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目入口：管理端点（创建/新版本/发布/测试点）限老师与管理员；
 * 题目详情面向所有登录用户（考生读题），绝不返回测试点内容。
 * 异常响应由 lunar-common 的全局异常处理器统一产出，本类不再自带 advice。
 */
@RestController
@RequestMapping("/api/v1/problems")
public class ProblemController {

    private final ProblemService problemService;

    public ProblemController(ProblemService problemService) {
        this.problemService = problemService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ProblemView>> create(
            @Valid @RequestBody CreateProblemRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ProblemView view = problemService.create(request.title(), request.statement(),
                request.sampleInput(), request.sampleOutput(), request.timeLimitMs(),
                request.memoryLimitKb(), request.outputLimitKb(), request.allowedLanguages(),
                request.testcases(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 分页列表（管理端）：keyword 模糊匹配标题；与创建/版本管理同权限，考生不通过该端点取题。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ProblemPage>> list(
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestParam(required = false) String keyword,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                problemService.list(keyword, page, pageSize, AccessScope.of(authentication))));
    }

    /**
     * 题目详情：**当前发布版本**的题面/样例/限制/语言（不含测试点）。
     *
     * 授权分两层：
     *   - TEACHER / ADMIN：必须是自己拥有的题目（越权与不存在同为 404，BE-01）；
     *   - CANDIDATE：只能读到**已发布**版本（草稿题面属于未公开内容，由 getCurrentPublished 保证）。
     *
     * 为什么考生不能按归属校验：题目的归属是"谁建的"，而考生该看到哪些题由**考试分配**决定，
     * 两者是不同的关系。更细的"只读本场分配给他的题"需要 contest 侧按 (考生, 题目) 反查，
     * 已作为待办记录（BACKEND_IMPROVEMENT_PLAN 的 BE-01 剩余项）。
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN', 'CANDIDATE')")
    public ResponseEntity<ApiResponse<VersionView>> get(
            @PathVariable String id,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        VersionView view = problemService.getCurrentPublished(id);
        if (isOperator(authentication)) {
            problemService.requireOwned(id, AccessScope.of(authentication));
        }
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 是否老师/管理员（决定详情接口走不走归属校验；考生只看已发布版本）。 */
    private static boolean isOperator(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals(Role.AUTHORITY_PREFIX + Role.TEACHER.name())
                        || authority.equals(Role.AUTHORITY_PREFIX + Role.ADMIN.name()));
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<VersionView>> createVersion(
            @PathVariable String id,
            @Valid @RequestBody CreateVersionRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        problemService.requireOwned(id, AccessScope.of(authentication));
        VersionView view = problemService.createNewVersion(id, request.baseVersionId(),
                request.title(), request.statement(), request.sampleInput(), request.sampleOutput(),
                request.timeLimitMs(), request.memoryLimitKb(), request.outputLimitKb(),
                request.allowedLanguages(), request.testcases(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<VersionView>>> listVersions(
            @PathVariable String id,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        problemService.requireOwned(id, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, problemService.listVersions(id)));
    }

    @PostMapping("/{id}/versions/{versionId}/publish")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<VersionView>> publishVersion(
            @PathVariable String id,
            @PathVariable String versionId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        problemService.requireOwned(id, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, problemService.publishVersion(id, versionId)));
    }

    /** 测试点视图：仅老师/管理员（限自己范围内的题目）；判题链路走内部端点，用服务身份访问。 */
    @GetMapping("/{id}/versions/{versionId}/testcases")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<TestcaseView>>> listTestcases(
            @PathVariable String id,
            @PathVariable String versionId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        problemService.requireOwned(id, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId, problemService.listTestcases(id, versionId)));
    }
}
