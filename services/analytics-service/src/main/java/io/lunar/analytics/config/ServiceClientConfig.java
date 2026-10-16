package io.lunar.analytics.config;

import io.lunar.analytics.infra.ContestInternalApi;
import io.lunar.common.client.HttpServiceClientFactory;
import io.lunar.common.identity.ServiceTokenApi;
import io.lunar.common.identity.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 服务间声明式客户端装配（与 submission/contest/ai 同一套约定）。
 *
 * 注入的 {@link RestClient.Builder} 由 lunar-common 提供且带负载均衡，baseUrl 可直接写服务名，
 * CONTEST_BASE_URL 可覆盖为固定地址排障。
 *
 * 分析侧只需要 contest 一家：参照数据全部来自考试快照。**没有 AuditRecorder**——本服务的写路径只有
 * MQ 投影（无用户动作要留痕），读取不产生审计账目。
 */
@Configuration(proxyBeanMethods = false)
public class ServiceClientConfig {

    /** 参照数据刷新与范围判断的取数入口。 */
    @Bean
    ContestInternalApi contestInternalApi(RestClient.Builder loadBalancedRestClientBuilder,
                                          @Value("${CONTEST_BASE_URL:http://contest-service}") String contestBaseUrl) {
        return HttpServiceClientFactory.create(ContestInternalApi.class, loadBalancedRestClientBuilder, contestBaseUrl);
    }

    /** 服务身份令牌（客户端凭证）：定时刷新与请求路径上的参照数据填充都没有用户上下文，只能用自己的服务身份。 */
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
