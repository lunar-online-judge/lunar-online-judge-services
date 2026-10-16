package io.lunar.ai.infra;

import io.lunar.ai.infra.dto.InternalSubmissionSummary;
import io.lunar.ai.infra.dto.ProblemVersionDetail;
import io.lunar.ai.infra.dto.SubmissionDetail;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.identity.ServiceTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 服务间取数适配层（**只用服务身份令牌**）。
 *
 * 为什么不用调用者的用户令牌：AI 作业由后台 worker 执行，那时请求上下文早已结束；
 * 而且 worker 可能重试到几分钟之后，转发的用户令牌早就过期了——这正是 BE-07 反复强调的
 * "不要把用户 Bearer 带进异步链路"。
 *
 * 本层只解 {@link ApiResponse} 信封；上游的 404/4xx 异常原样抛出（HttpClientErrorException），
 * 由处理器映射成稳定的域错误。
 */
@Component
public class AiUpstreamClient {

    private final SubmissionInternalApi submissionApi;
    private final ProblemInternalApi problemApi;
    private final ContestInternalApi contestApi;
    private final ServiceTokenProvider serviceTokenProvider;

    public AiUpstreamClient(SubmissionInternalApi submissionApi, ProblemInternalApi problemApi,
                            ContestInternalApi contestApi,ServiceTokenProvider serviceTokenProvider) {
        this.submissionApi = submissionApi;
        this.problemApi = problemApi;
        this.contestApi = contestApi;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    /** 提交详情（含源码，服务间端点）：不存在时上游 404。 */
    public SubmissionDetail submissionDetail(String submissionId) {
        return unwrap(submissionApi.detail(submissionId, bearer()));
    }

    /** 按考试+题目批量取提交（含源码）：语义查重的语料来源。 */
    public List<InternalSubmissionSummary> listSubmissions(String contestId, String problemId, int limit) {
        List<InternalSubmissionSummary> submissions =
                unwrap(submissionApi.list(contestId, problemId, limit, bearer()));
        return submissions == null ? List.of() : submissions;
    }

    /** 指定题目版本详情（按考试锁定版本取题面，不受全局发布影响）。 */
    public ProblemVersionDetail problemVersion(String problemId, String versionId) {
        return unwrap(problemApi.version(problemId, versionId, bearer()));
    }

    /**
     * 某学生在这场考试的风险事件分页（服务身份）。
     * 上游不可用时抛异常由作业层按错误码处理；返回 null 表示"没有证据"，由调用方决定怎么表达。
     */
    
    private String bearer() {
        return "Bearer " + serviceTokenProvider.token();
    }

    private static <T> T unwrap(ApiResponse<T> response) {
        return response == null ? null : response.data();
    }
}
