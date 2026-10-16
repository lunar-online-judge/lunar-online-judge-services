package io.lunar.contest.infra;

import io.lunar.common.client.LoadBalancedRestClientAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配冒烟（RestClient 手写客户端时代即有同名测试；改用声明式接口后恢复，并覆盖三种装配分支）：
 * 客户端写 {@code http://auth-service} 能通，前提是注入到的 RestClient.Builder 带负载均衡拦截器；
 * 而 Boot 自动配置默认也会提供一个 RestClient.Builder，两者并存会让注入点歧义——
 * 这里验证带负载均衡的那个是唯一的一个，且 Boot 的 Builder 已退避。
 */
class LoadBalancedRestClientAssemblyTest {

    private static final String LOAD_BALANCER_CLASS =
            "org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoadBalancedRestClientAutoConfiguration.class));

    @Test
    void loadBalancedBuilderIsTheOnlyRestClientBuilder() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RestClient.Builder.class);
            assertThat(context).hasBean("loadBalancedRestClientBuilder");
        });
    }

    @Test
    void bootBuilderBacksOffWhenLoadBalancedBuilderPresent() {
        runner.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(RestClient.Builder.class);
                    assertThat(context).hasBean("loadBalancedRestClientBuilder");
                });
    }

    @Test
    void autoConfigurationBacksOffWithoutLoadBalancerOnClasspath() {
        // 条件为 LoadBalancerClientFactory 在类路径：模拟"不按服务名寻址"的服务
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LoadBalancedRestClientAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(LOAD_BALANCER_CLASS))
                .run(context -> assertThat(context).doesNotHaveBean(RestClient.Builder.class));
    }
}
