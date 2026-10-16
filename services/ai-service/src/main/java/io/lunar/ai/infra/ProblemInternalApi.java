package io.lunar.ai.infra;

import io.lunar.ai.infra.dto.ProblemVersionDetail;
import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * problem-service 服务间接口（{@code /api/v1/internal/problems/...}）的声明式 HTTP 接口。
 * 用**考试锁定版本**取题面（BE-04）：题目全局再发布新版本也不该改变 AI 对这次提交的评审依据。
 */
@HttpExchange("/api/v1/internal/problems")
public interface ProblemInternalApi {

    @GetExchange("/{problemId}/versions/{versionId}")
    ApiResponse<ProblemVersionDetail> version(@PathVariable("problemId") String problemId,
                                             @PathVariable("versionId") String versionId,
                                             @RequestHeader("Authorization") String authorization);
}
