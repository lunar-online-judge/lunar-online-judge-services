package io.lunar.submission.config;

import io.lunar.common.audit.AuditRecorder;
import io.lunar.common.audit.AuditServiceApi;
import io.lunar.common.client.HttpServiceClientFactory;
import io.lunar.submission.infra.ContestServiceApi;
import io.lunar.submission.infra.ProblemInternalApi;
import io.lunar.submission.infra.ProblemServiceApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 服务间声明式客户端装配（docs/middleware.md"服务发现"）：contest 与 problem 各一个 Bean。
 * 注入的 {@link RestClient.Builder} 由 lunar-common 提供且带负载均衡，baseUrl 可直接写服务名，
 * 环境变量（CONTEST_BASE_URL / PROBLEM_BASE_URL）可覆盖为固定地址排障。
 *
 * 为什么显式装配：Spring Framework 6.2 只有 {@link HttpServiceClientFactory} 这一层
 * （HttpServiceProxyFactory），接口的自动注册（@ImportHttpServices 分组）要 Framework 7 / Boot 4。
 */
@Configuration(proxyBeanMethods = false)
public class ServiceClientConfig {

    @Bean
    ContestServiceApi contestServiceApi(RestClient.Builder loadBalancedRestClientBuilder,
                                        @Value("${CONTEST_BASE_URL:http://contest-service}") String contestBaseUrl) {
        return HttpServiceClientFactory.create(ContestServiceApi.class, loadBalancedRestClientBuilder, contestBaseUrl);
    }

    @Bean
    ProblemServiceApi problemServiceApi(RestClient.Builder loadBalancedRestClientBuilder,
                                        @Value("${PROBLEM_BASE_URL:http://problem-service}") String problemBaseUrl) {
        return HttpServiceClientFactory.create(ProblemServiceApi.class, loadBalancedRestClientBuilder, problemBaseUrl);
    }

    /** 判题链路的测试点查询（服务间内部端点，同样按服务名寻址）。 */
    @Bean
    ProblemInternalApi problemInternalApi(RestClient.Builder loadBalancedRestClientBuilder,
                                          @Value("${PROBLEM_BASE_URL:http://problem-service}") String problemBaseUrl) {
        return HttpServiceClientFactory.create(ProblemInternalApi.class, loadBalancedRestClientBuilder, problemBaseUrl);
    }

    /** 审计账本写入：契约被四个服务共用，故复用 lunar-common 的 {@link AuditServiceApi}。 */
    @Bean
    AuditServiceApi auditServiceApi(RestClient.Builder loadBalancedRestClientBuilder,
                                    @Value("${AUDIT_BASE_URL:http://audit-service}") String auditBaseUrl) {
        return HttpServiceClientFactory.create(AuditServiceApi.class, loadBalancedRestClientBuilder, auditBaseUrl);
    }

    @Bean
    AuditRecorder auditRecorder(AuditServiceApi auditServiceApi) {
        return new AuditRecorder(auditServiceApi);
    }
}
