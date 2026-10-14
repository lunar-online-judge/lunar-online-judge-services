package io.lunar.common.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * 服务间调用的负载均衡装配（docs/middleware.md"服务发现"）：
 * 提供全应用唯一的 {@code @LoadBalanced RestClient.Builder}，调用方用它构造客户端后
 * 即可直接写服务名（如 {@code http://auth-service}）而不是 host:port，由 Nacos 注册表解析实例。
 *
 * 条件为负载均衡器在类路径（spring-cloud-starter-loadbalancer）：不按服务名寻址的服务整体退避，
 * 行为与接入前一致，不需要用到本模块的这项能力。
 * 该 Bean 存在时 Boot 自动配置的 RestClient.Builder 会因 @ConditionalOnMissingBean 退避，
 * 因此各服务注入 RestClient.Builder 拿到的就是这个带负载均衡解析的 Builder。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory")
public class LoadBalancedRestClientAutoConfiguration {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
