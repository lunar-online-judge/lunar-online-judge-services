package io.lunar.problem.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.problem.domain.ProblemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    public ResponseEntity<ApiResponse<ProblemService.ProblemView>> create(
            @Valid @RequestBody CreateProblemRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ProblemService.ProblemView view = problemService.create(request.title(), request.statement(),
                request.sampleInput(), request.sampleOutput(), request.timeLimitMs(),
                request.memoryLimitKb(), request.outputLimitKb(), request.allowedLanguages(),
                request.testcases(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 分页列表（管理端）：keyword 模糊匹配标题。 */
    @GetMapping
    public ResponseEntity<ApiResponse<ProblemService.ProblemPage>> list(
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, problemService.list(keyword, page, pageSize)));
    }

    /** 题目详情（考生视角）：当前发布版本的题面/样例/限制/语言；无测试点。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProblemService.VersionView>> get(
            @PathVariable String id,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, problemService.getCurrentPublished(id)));
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ProblemService.VersionView>> createVersion(
            @PathVariable String id,
            @Valid @RequestBody CreateVersionRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ProblemService.VersionView view = problemService.createNewVersion(id, request.baseVersionId(),
                request.title(), request.statement(), request.sampleInput(), request.sampleOutput(),
                request.timeLimitMs(), request.memoryLimitKb(), request.outputLimitKb(),
                request.allowedLanguages(), request.testcases(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ProblemService.VersionView>>> listVersions(
            @PathVariable String id,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, problemService.listVersions(id)));
    }

    @PostMapping("/{id}/versions/{versionId}/publish")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ProblemService.VersionView>> publishVersion(
            @PathVariable String id,
            @PathVariable String versionId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, problemService.publishVersion(id, versionId)));
    }

    /** 测试点视图：仅老师/管理员（判题链路后续走服务间接口复用本查询）。 */
    @GetMapping("/{id}/versions/{versionId}/testcases")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ProblemService.TestcaseView>>> listTestcases(
            @PathVariable String id,
            @PathVariable String versionId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, problemService.listTestcases(id, versionId)));
    }

    public record CreateProblemRequest(@NotBlank String title, @NotBlank String statement,
                                       String sampleInput, String sampleOutput,
                                       Integer timeLimitMs, Integer memoryLimitKb, Integer outputLimitKb,
                                       String allowedLanguages, List<ProblemService.TestcasePayload> testcases) {}

    public record CreateVersionRequest(String baseVersionId, String title, String statement,
                                       String sampleInput, String sampleOutput,
                                       Integer timeLimitMs, Integer memoryLimitKb, Integer outputLimitKb,
                                       String allowedLanguages, List<ProblemService.TestcasePayload> testcases) {}
}
