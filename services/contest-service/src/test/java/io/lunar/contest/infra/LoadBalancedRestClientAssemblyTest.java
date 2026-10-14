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
 * 服务名寻址的关键装配事实（放在本模块是因为只有调用方才有 loadbalancer + 测试依赖）。
 *
 * 客户端写 {@code http://auth-service} 能通，前提是注入到的 RestClient.Builder 带着负载均衡拦截器；
 * 而 Boot 自动配置默认也会提供一个 RestClient.Builder，两者并存会让注入点歧义。
 * 这里钉住：本模块的 Builder 存在时 Boot 的会退避，注入到的就是带负载均衡的那个。
 */
class LoadBalancedRestClientAssemblyTest {

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
    void bootAutoConfiguredBuilderBacksOff() {
        runner.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(RestClient.Builder.class);
                    assertThat(context).hasBean("loadBalancedRestClientBuilder");
                });
    }

    @Test
    void backsOffWhenLoadBalancerAbsent() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.cloud.loadbalancer"))
                .run(context -> assertThat(context).doesNotHaveBean(RestClient.Builder.class));
    }
}
