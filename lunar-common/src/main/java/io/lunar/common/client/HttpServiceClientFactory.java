package io.lunar.common.client;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * 声明式 HTTP 接口（{@code @HttpExchange}）的装配口：把接口类型变成可注入的 Bean。
 *
 * 用法（各服务的 config 包内，一个上游一个 Bean）：
 * <pre>{@code
 * @Bean
 * AuthServiceApi authServiceApi(RestClient.Builder loadBalancedRestClientBuilder,
 *                               @Value("${AUTH_BASE_URL:http://auth-service}") String authBaseUrl) {
 *     return HttpServiceClientFactory.create(AuthServiceApi.class, loadBalancedRestClientBuilder, authBaseUrl);
 * }
 * }</pre>
 *
 * 为什么需要它：Spring Framework 6.2 只有 {@link HttpServiceProxyFactory}，
 * 接口的自动注册（{@code @ImportHttpServices} 分组）要 Spring Framework 7 / Boot 4 才有，
 * 所以本项目在 Boot 3.5 上显式装配——顺带让"哪个服务调哪个上游、baseUrl 从哪来"一目了然。
 */
public final class HttpServiceClientFactory {

    private HttpServiceClientFactory() {
    }

    /**
     * 用给定的 Builder 与 baseUrl 构造接口代理。
     *
     * @param apiType  声明式接口类型（{@code @HttpExchange}）
     * @param builder  带负载均衡的 {@code RestClient.Builder}，baseUrl 允许直接写服务名
     * @param baseUrl  上游地址：服务名（经 Nacos 解析）或环境变量覆盖的固定地址
     */
    public static <T> T create(Class<T> apiType, RestClient.Builder builder, String baseUrl) {
        RestClient restClient = builder.baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(apiType);
    }
}
