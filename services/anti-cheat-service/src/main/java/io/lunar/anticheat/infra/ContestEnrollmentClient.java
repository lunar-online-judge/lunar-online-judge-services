package io.lunar.anticheat.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 报名校验（BE-03 剩余项）：把"这条风险事件是不是这个考生在这场考试里"交给 contest 判定。
 *
 * 用**考生自己的令牌**转发调用 contest 的内部会话接口（该端点要求 CANDIDATE），这样 anti-cheat
 * 不需要服务身份，而报名/会话判定仍由 contest 作为唯一事实源。
 *
 * 失败策略：
 * - 上游返回 4xx（未报名 / 无会话）→ **拒绝**，回 403；
 * - **连接失败 → fail-open 放行**。理由：监考证据宁可多收一条，也不能因为 contest 抖动就丢掉
 *   "考生开了调试器"这种事实。日志里会留下 fail-open 痕迹，便于事后核对。
 */
@Component
public class ContestEnrollmentClient {

    private static final Logger log = LoggerFactory.getLogger(ContestEnrollmentClient.class);

    private final RestClient contestRestClient;

    public ContestEnrollmentClient(RestClient contestRestClient) {
        this.contestRestClient = contestRestClient;
    }

    /** true = 可以接受这条上报（已报名，或上游不可用时按 fail-open）。 */
    public boolean isEnrolled(String contestId, String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            // 没有令牌就无法判定；这是调用方的问题，按拒绝处理（不该出现：端点要求 CANDIDATE）
            return false;
        }
        try {
            contestRestClient.get()
                    .uri("/api/v1/internal/contests/{contestId}/session", contestId)
                    .header("Authorization", "Bearer " + bearerToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException exception) {
            log.info("AUDIT event=RISK_REPORT_REJECTED contestId={} reason=not-enrolled status={}",
                    contestId, exception.getStatusCode().value());
            return false;
        } catch (Exception exception) {
            log.warn("报名校验不可用，按 fail-open 接受本次上报：contestId={} reason={}",
                    contestId, exception.getMessage());
            return true;
        }
    }
}
