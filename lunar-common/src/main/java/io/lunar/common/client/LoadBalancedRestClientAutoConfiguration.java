package io.lunar.common.client;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * 服务间调用的负载均衡装配（docs/middleware.md"服务发现"）：
 * 提供全应用唯一的 {@code @LoadBalanced RestClient.Builder}，声明式 HTTP 接口
 * （{@code @HttpExchange}，装配见 {@link HttpServiceClientFactory}）与手写客户端都用它，
 * 因此可以直接写服务名（如 {@code http://auth-service}）而不是 host:port。
 *
 * 两个关键点：
 * 1. 交给 Boot 的 {@link RestClientBuilderConfigurer} 配置，从而继承 Boot 默认的
 *    HttpMessageConverters、{@code spring.http.client.*} 超时与 observation——自己 new 一个 Builder 会丢掉这些；
 * 2. {@code before = RestClientAutoConfiguration} 让本 Bean 先注册，Boot 的
 *    {@code @ConditionalOnMissingBean} 才会退避，避免出现两个 RestClient.Builder 造成注入歧义。
 *
 * 条件为负载均衡器在类路径（spring-cloud-starter-loadbalancer）：不按服务名寻址的服务整体退避。
 */
@AutoConfiguration(before = RestClientAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory")
public class LoadBalancedRestClientAutoConfiguration {

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(ObjectProvider<RestClientBuilderConfigurer> configurerProvider) {
        // 有 Boot 的 configurer 就用它（继承 HttpMessageConverters / spring.http.client.* 超时 / observation）；
        // 它不在时（例如只加载本自动配置的切片测试）退化为默认 Builder，不因缺这个 Bean 而启动失败
        RestClientBuilderConfigurer configurer = configurerProvider.getIfAvailable(RestClientBuilderConfigurer::new);
        return configurer.configure(RestClient.builder());
    }
}
