package io.lunar.contest.config;

import io.lunar.common.audit.AuditRecorder;
import io.lunar.common.audit.AuditServiceApi;
import io.lunar.common.client.HttpServiceClientFactory;
import io.lunar.common.identity.ServiceTokenApi;
import io.lunar.common.identity.ServiceTokenProvider;
import io.lunar.contest.infra.AuthServiceApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 服务间声明式客户端装配（docs/middleware.md"服务发现"）：一个上游一个 Bean。
 * 注入的 {@link RestClient.Builder} 由 lunar-common 提供且带负载均衡，baseUrl 可直接写服务名，
 * 环境变量（AUTH_BASE_URL）可覆盖为固定地址排障。
 *
 * 为什么显式装配：Spring Framework 6.2 只有 {@link HttpServiceClientFactory} 这一层
 * （HttpServiceProxyFactory），接口的自动注册（@ImportHttpServices 分组）要 Framework 7 / Boot 4。
 */
@Configuration(proxyBeanMethods = false)
public class ServiceClientConfig {

    @Bean
    AuthServiceApi authServiceApi(RestClient.Builder loadBalancedRestClientBuilder,
                                  @Value("${AUTH_BASE_URL:http://auth-service}") String authBaseUrl) {
        return HttpServiceClientFactory.create(AuthServiceApi.class, loadBalancedRestClientBuilder, authBaseUrl);
    }

    /** 审计账本写入：契约被四个服务共用，故复用 lunar-common 的 {@link AuditServiceApi}。 */
    @Bean
    AuditServiceApi auditServiceApi(RestClient.Builder loadBalancedRestClientBuilder,
                                    @Value("${AUDIT_BASE_URL:http://audit-service}") String auditBaseUrl) {
        return HttpServiceClientFactory.create(AuditServiceApi.class, loadBalancedRestClientBuilder, auditBaseUrl);
    }

    @Bean
    AuditRecorder auditRecorder(AuditServiceApi auditServiceApi, ServiceTokenProvider serviceTokenProvider) {
        return new AuditRecorder(auditServiceApi, serviceTokenProvider);
    }

    /** 服务身份令牌（客户端凭证）：定时任务没有用户令牌，用它换取 SERVICE 身份写账本。 */
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
