package io.lunar.ai.config;

import io.lunar.ai.infra.ContestInternalApi;
import io.lunar.ai.infra.ProblemInternalApi;
import io.lunar.ai.infra.SubmissionInternalApi;
import io.lunar.common.client.HttpServiceClientFactory;
import io.lunar.common.identity.ServiceTokenApi;
import io.lunar.common.identity.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 服务间声明式客户端装配（与 submission/contest 同一套约定）。
 *
 * 注入的 {@link RestClient.Builder} 由 lunar-common 提供且带负载均衡，baseUrl 可直接写服务名，
 * SUBMISSION_BASE_URL / PROBLEM_BASE_URL 可覆盖为固定地址排障。
 *
 * **没有 AuditServiceApi/AuditRecorder**：AI 的审计留痕走本地 Outbox 的 {@code lunar-audit-recorded}
 * 异步投递（BE-07 的既定路径），不再同步调 audit-service——同步调用会在 audit 停机时丢账。
 */
@Configuration(proxyBeanMethods = false)
public class ServiceClientConfig {

    @Bean
    SubmissionInternalApi submissionInternalApi(RestClient.Builder loadBalancedRestClientBuilder,
                                                @Value("${SUBMISSION_BASE_URL:http://submission-service}") String submissionBaseUrl) {
        return HttpServiceClientFactory.create(SubmissionInternalApi.class, loadBalancedRestClientBuilder,
                submissionBaseUrl);
    }

    @Bean
    ProblemInternalApi problemInternalApi(RestClient.Builder loadBalancedRestClientBuilder,
                                          @Value("${PROBLEM_BASE_URL:http://problem-service}") String problemBaseUrl) {
        return HttpServiceClientFactory.create(ProblemInternalApi.class, loadBalancedRestClientBuilder, problemBaseUrl);
    }

    /** 考试快照：取 ownerTeacherId 做 BE-01 资源范围判断（老师因此能用上 AI，而不是只能 ADMIN）。 */
    @Bean
    ContestInternalApi contestInternalApi(RestClient.Builder loadBalancedRestClientBuilder,
                                          @Value("${CONTEST_BASE_URL:http://contest-service}") String contestBaseUrl) {
        return HttpServiceClientFactory.create(ContestInternalApi.class, loadBalancedRestClientBuilder, contestBaseUrl);
    }

    /** 风险事件（服务身份）：风险辅助研判的证据来源。 */
    @Bean
    ServiceTokenApi serviceTokenApi(RestClient.Builder loadBalancedRestClientBuilder,
                                    @Value("${AUTH_BASE_URL:http://auth-service}") String authBaseUrl) {
        return HttpServiceClientFactory.create(ServiceTokenApi.class, loadBalancedRestClientBuilder, authBaseUrl);
    }

    @Bean
    ServiceTokenProvider serviceTokenProvider(ServiceTokenApi serviceTokenApi,
                                              @Value("${spring.application.name}") String serviceName,
                                              @Value("${service.identity.client-secret}") String clientSecret) {
        return new ServiceTokenProvider(serviceTokenApi, serviceName, clientSecret);
    }
}
