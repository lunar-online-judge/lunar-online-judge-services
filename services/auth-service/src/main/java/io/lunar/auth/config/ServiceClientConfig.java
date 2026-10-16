package io.lunar.auth.config;

import io.lunar.common.audit.AuditRecorder;
import io.lunar.common.audit.AuditServiceApi;
import io.lunar.common.client.HttpServiceClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 服务间声明式客户端装配（docs/middleware.md"服务发现"）：一个上游一个 Bean。
 * 注入的 {@link RestClient.Builder} 由 lunar-common 提供且带负载均衡，baseUrl 可直接写服务名，
 * 环境变量（AUDIT_BASE_URL）可覆盖为固定地址排障。
 *
 * auth-service 目前只往外写审计账本（学生账号创建/密码重置由本服务执行、动作留痕到 audit）。
 */
@Configuration(proxyBeanMethods = false)
public class ServiceClientConfig {

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
