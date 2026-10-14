package io.lunar.submission.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * contest / problem 服务间查询客户端（转发学生令牌）：
 * - contest：会话与竞赛状态提交判定（canSubmit）；
 * - problem：题目当前发布版本（语言白名单、判题限制、版本 ID）——考生视角不返回测试点，符合契约。
 * 寻址：按服务名经 Nacos 解析实例，CONTEST_BASE_URL / PROBLEM_BASE_URL 可覆盖为固定地址用于排障。
 */
@Component
public class UpstreamClient {

    private final RestClient contest;
    private final RestClient problem;

    public UpstreamClient(RestClient.Builder loadBalancedRestClientBuilder,
                          @Value("${CONTEST_BASE_URL:http://contest-service}") String contestBaseUrl,
                          @Value("${PROBLEM_BASE_URL:http://problem-service}") String problemBaseUrl) {
        this.contest = loadBalancedRestClientBuilder.baseUrl(contestBaseUrl).build();
        this.problem = loadBalancedRestClientBuilder.baseUrl(problemBaseUrl).build();
    }

    private static String bearer(String token) {
        String rawToken = token == null ? "" : token.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + rawToken;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrap(Map<String, Object> body) {
        return body == null ? null : (Map<String, Object>) body.get("data");
    }

    /** 提交判定：canSubmit/sessionStatus/contestStatus/remainingSeconds（contest-service）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkSession(String contestId, String operatorToken) {
        return unwrap(contest.get()
                .uri("/api/v1/internal/contests/{id}/session", contestId)
                .header("Authorization", bearer(operatorToken))
                .retrieve()
                .body(Map.class));
    }

    /** 题目当前发布版本（problem-service，考生视角）。不存在/未发布时抛 404。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> currentProblemVersion(String problemId, String operatorToken) {
        return unwrap(problem.get()
                .uri("/api/v1/problems/{id}", problemId)
                .header("Authorization", bearer(operatorToken))
                .retrieve()
                .body(Map.class));
    }
}
