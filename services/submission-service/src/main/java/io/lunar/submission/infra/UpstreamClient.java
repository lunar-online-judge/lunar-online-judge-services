package io.lunar.submission.infra;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.identity.ServiceTokenProvider;
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
    private final ServiceTokenProvider serviceTokenProvider;

    public UpstreamClient(ContestServiceApi contestApi, ProblemServiceApi problemApi,
                          ProblemInternalApi problemInternalApi, ServiceTokenProvider serviceTokenProvider) {
        this.contestApi = contestApi;
        this.problemApi = problemApi;
        this.problemInternalApi = problemInternalApi;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    /** 提交判定：canSubmit/sessionStatus/contestStatus/remainingSeconds（contest-service）。 */
    public Map<String, Object> checkSession(String contestId, String operatorToken) {
        return unwrap(contestApi.checkSession(contestId, bearer(operatorToken)));
    }

    /** 题目当前发布版本（problem-service，考生视角）。不存在/未发布时抛 404。 */
    public Map<String, Object> currentProblemVersion(String problemId, String operatorToken) {
        return unwrap(problemApi.currentVersion(problemId, bearer(operatorToken)));
    }

    /** 判题链路的测试点（problem 服务间端点）：**用服务身份令牌**调用，超限时上游返回 413。 */
    public List<Map<String, Object>> problemTestcases(String problemId, String versionId) {
        ApiResponse<List<Map<String, Object>>> response =
                problemInternalApi.testcases(problemId, versionId, bearer(serviceTokenProvider.token()));
        return response == null || response.data() == null ? List.of() : response.data();
    }

    /** 考试给该题目锁定的版本（BE-04）；题目不在考试里时上游 404，异常原样抛出由调用方映射。 */
    public String lockedProblemVersion(String contestId, String problemId, String operatorToken) {
        Map<String, Object> locked = unwrap(
                contestApi.lockedProblemVersion(contestId, problemId, bearer(operatorToken)));
        return locked.get("problemVersionId") == null ? null : String.valueOf(locked.get("problemVersionId"));
    }

    /** 指定版本详情（BE-04）：按锁定版本取判题限制，同样用服务身份。 */
    public Map<String, Object> problemVersion(String problemId, String versionId) {
        return unwrap(problemInternalApi.version(problemId, versionId, bearer(serviceTokenProvider.token())));
    }

    private static String bearer(String token) {
        String rawToken = token == null ? "" : token.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + rawToken;
    }

    private static Map<String, Object> unwrap(ApiResponse<Map<String, Object>> response) {
        return response == null ? null : response.data();
    }
}
