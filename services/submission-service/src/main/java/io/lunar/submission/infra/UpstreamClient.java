package io.lunar.submission.infra;

import io.lunar.common.api.ApiResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * contest / problem 服务间查询适配层（转发学生令牌）：
 * - contest：会话与竞赛状态提交判定（canSubmit）；
 * - problem：题目当前发布版本（语言白名单、判题限制、版本 ID）——考生视角不返回测试点，符合契约；
 * - problem（内部端点）：判题链路取测试点，由 submission 放进 created 事件送到评测引擎。
 *
 * 走 Spring 原生声明式 HTTP 接口（{@link ContestServiceApi} / {@link ProblemServiceApi} /
 * {@link ProblemInternalApi}，代理 Bean 见 io.lunar.submission.config.ServiceClientConfig），
 * 按服务名经 Nacos + LoadBalancer 解析实例，CONTEST_BASE_URL / PROBLEM_BASE_URL 可覆盖为固定地址排障；
 * 本层只负责解 {@link ApiResponse} 信封，4xx/5xx 异常原样抛出、由调用方映射为域异常。
 */
@Component
public class UpstreamClient {

    private final ContestServiceApi contestApi;
    private final ProblemServiceApi problemApi;
    private final ProblemInternalApi problemInternalApi;

    public UpstreamClient(ContestServiceApi contestApi, ProblemServiceApi problemApi,
                          ProblemInternalApi problemInternalApi) {
        this.contestApi = contestApi;
        this.problemApi = problemApi;
        this.problemInternalApi = problemInternalApi;
    }

    /** 提交判定：canSubmit/sessionStatus/contestStatus/remainingSeconds（contest-service）。 */
    public Map<String, Object> checkSession(String contestId, String operatorToken) {
        return unwrap(contestApi.checkSession(contestId, bearer(operatorToken)));
    }

    /** 题目当前发布版本（problem-service，考生视角）。不存在/未发布时抛 404。 */
    public Map<String, Object> currentProblemVersion(String problemId, String operatorToken) {
        return unwrap(problemApi.currentVersion(problemId, bearer(operatorToken)));
    }

    /** 判题链路的测试点（problem-service 服务间端点）：超限时上游返回 413，异常原样抛出。 */
    public List<Map<String, Object>> problemTestcases(String problemId, String versionId, String operatorToken) {
        ApiResponse<List<Map<String, Object>>> response =
                problemInternalApi.testcases(problemId, versionId, bearer(operatorToken));
        return response == null || response.data() == null ? List.of() : response.data();
    }

    private static String bearer(String token) {
        String rawToken = token == null ? "" : token.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + rawToken;
    }

    private static Map<String, Object> unwrap(ApiResponse<Map<String, Object>> response) {
        return response == null ? null : response.data();
    }
}
