package io.lunar.anticheat.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 到 contest-service 的负载均衡客户端（报名校验用）。
 *
 * 单独命名一个 {@code @LoadBalanced} 的 Builder 再据此构建 RestClient：如果直接把
 * {@code @LoadBalanced RestClient.Builder} 暴露成通用类型，会和 Spring Boot 自动配置的那个
 * Builder 一起造成注入歧义（两个同类型 Bean），启动即失败。
 */
@Configuration(proxyBeanMethods = false)
public class ContestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder contestLoadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient contestRestClient(
            @Qualifier("contestLoadBalancedRestClientBuilder") RestClient.Builder builder) {
        return builder.baseUrl("http://contest-service").build();
    }
}
