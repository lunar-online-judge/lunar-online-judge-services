package io.lunar.problem.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.problem.domain.ProblemService;
import io.lunar.problem.domain.dto.TestcaseView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间接口（docs/api.md 端点总表）：gateway 不配置 {@code /api/v1/internal/**} 路由，
 * 因此只能内网直连访问；调用方需持有效令牌——沿用仓库既有约定（与 audit 的服务间写入一致），
 * "服务间独立身份（客户端凭证）"落地后再收紧。
 */
@RestController
@RequestMapping("/api/v1/internal/problems")
public class InternalProblemController {

    private final ProblemService problemService;

    public InternalProblemController(ProblemService problemService) {
        this.problemService = problemService;
    }

    /** 判题链路取测试点（docs/architecture.md §8 第 5 步）：submission 取到后随事件送到 judge。 */
    @GetMapping("/{problemId}/versions/{versionId}/testcases")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TestcaseView>>> listTestcasesForJudging(
            @PathVariable String problemId,
            @PathVariable String versionId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                problemService.listTestcasesForJudging(problemId, versionId)));
    }
}
