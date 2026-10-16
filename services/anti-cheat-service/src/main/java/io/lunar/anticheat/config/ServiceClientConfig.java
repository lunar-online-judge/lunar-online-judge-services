package io.lunar.anticheat.config;

import io.lunar.anticheat.infra.AuditServiceApi;
import io.lunar.common.client.HttpServiceClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 服务间声明式客户端装配（docs/middleware.md"服务发现"）：审计账本一个 Bean。
 * 注入的 {@link RestClient.Builder} 由 lunar-common 提供且带负载均衡，baseUrl 可直接写服务名，
 * AUDIT_BASE_URL 可覆盖为固定地址排障。
 *
 * 为什么显式装配：Spring Framework 6.2 只有 {@link HttpServiceClientFactory} 这一层
 * （HttpServiceProxyFactory），接口的自动注册（@ImportHttpServices 分组）要 Framework 7 / Boot 4。
 */
@Configuration(proxyBeanMethods = false)
public class ServiceClientConfig {

    @Bean
    AuditServiceApi auditServiceApi(RestClient.Builder loadBalancedRestClientBuilder,
                                    @Value("${AUDIT_BASE_URL:http://audit-service}") String auditBaseUrl) {
        return HttpServiceClientFactory.create(AuditServiceApi.class, loadBalancedRestClientBuilder, auditBaseUrl);
    }
}
