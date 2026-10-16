package io.lunar.submission.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.submission.domain.SubmissionService;
import io.lunar.submission.domain.dto.InternalSubmissionDetail;
import io.lunar.submission.domain.dto.InternalSubmissionSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间提交详情（**含源码**）。
 *
 * 只认服务身份 `roles=["SERVICE"]`，不经网关（gateway 无 `/api/v1/internal/**` 路由）：
 * 源码是学生作品，不能让"内网 + 任意已认证令牌"取走（与 problem 测试点接口同一收紧原则，BE-01）。
 *
 * 当前调用方：ai-service 的代码评审作业（后台 worker 用自身服务身份调用）。
 */
@RestController
@RequestMapping("/api/v1/internal/submissions")
public class InternalSubmissionController {

    /** 批量接口的默认条数（上限由服务端在 Service 层夹取，见 {@code MAX_INTERNAL_LIST_SIZE}）。 */
    private static final String DEFAULT_LIST_LIMIT = "200";

    private final SubmissionService submissionService;

    public InternalSubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping("/{submissionId}")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<ApiResponse<InternalSubmissionDetail>> detail(
            @PathVariable String submissionId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                submissionService.internalDetail(submissionId)));
    }

    /**
     * 批量摘要（**含源码**）：供 ai-service 的语义查重取"同一场考试同一道题"的语料。
     *
     * {@code limit} 由服务端夹取（默认 200、上限 500），调用方无法把这个接口变成全库导出。
     */
    @GetMapping
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<ApiResponse<List<InternalSubmissionSummary>>> list(
            @RequestParam String contestId,
            @RequestParam String problemId,
            @RequestParam(defaultValue = DEFAULT_LIST_LIMIT) int limit,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                submissionService.internalList(contestId, problemId, limit)));
    }
}
