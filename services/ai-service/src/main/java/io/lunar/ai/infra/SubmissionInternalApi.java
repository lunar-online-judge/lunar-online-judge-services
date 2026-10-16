package io.lunar.ai.infra;

import io.lunar.ai.infra.dto.InternalSubmissionSummary;
import io.lunar.ai.infra.dto.SubmissionDetail;
import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;

/**
 * submission-service 服务间接口（{@code /api/v1/internal/submissions/...}）的声明式 HTTP 接口。
 * 代理 Bean 由 {@code io.lunar.ai.config.ServiceClientConfig} 装配，baseUrl 默认服务名。
 *
 * 该端点不经网关且**只接受服务身份令牌**：它返回源码，不能靠"内网 + 任意已认证令牌"取走。
 */
@HttpExchange("/api/v1/internal/submissions")
public interface SubmissionInternalApi {

    @GetExchange("/{submissionId}")
    ApiResponse<SubmissionDetail> detail(@PathVariable("submissionId") String submissionId,
                                         @RequestHeader("Authorization") String authorization);

    /** 按考试+题目批量取提交（语义查重取语料）：条数上限由上游服务端夹取。 */
    @GetExchange
    ApiResponse<List<InternalSubmissionSummary>> list(@RequestParam("contestId") String contestId,
                                                     @RequestParam("problemId") String problemId,
                                                     @RequestParam("limit") int limit,
                                                     @RequestHeader("Authorization") String authorization);
}
