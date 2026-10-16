package io.lunar.common.identity;

import io.lunar.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * 服务身份令牌的获取与缓存：没有用户上下文的调用方（定时任务、MQ 消费者）用它换取一个
 * {@code roles=["SERVICE"]} 的令牌去调用内部接口，从而不再"借用"某个用户的令牌。
 *
 * 令牌缓存到到期前 {@link #REFRESH_MARGIN} 自动续签，并发调用只发一次请求；
 * 取不到令牌时抛异常，由调用方决定降级（审计留痕是尽力而为，业务主流程不受影响）。
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    /** 提前续签的余量：避免"刚取到就过期"。 */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final ServiceTokenApi serviceTokenApi;
    private final String clientId;
    private final String clientSecret;
    private volatile ServiceTokenResponse cached;

    public ServiceTokenProvider(ServiceTokenApi serviceTokenApi, String clientId, String clientSecret) {
        this.serviceTokenApi = serviceTokenApi;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** 取服务令牌（命中缓存直接返回）；签发失败抛 {@link IllegalStateException}。 */
    public String token() {
        if (isFresh(cached)) {
            return cached.accessToken();
        }
        synchronized (this) {
            if (isFresh(cached)) {
                return cached.accessToken();
            }
            ServiceTokenResponse issued = issue();
            cached = issued;
            log.debug("服务令牌已签发 clientId={} expiresAt={}", clientId, issued.expiresAt());
            return issued.accessToken();
        }
    }

    private boolean isFresh(ServiceTokenResponse token) {
        return token != null && Instant.now().isBefore(token.expiresAt().minus(REFRESH_MARGIN));
    }

    private ServiceTokenResponse issue() {
        ApiResponse<ServiceTokenResponse> response =
                serviceTokenApi.issue(new ServiceTokenRequest(clientId, clientSecret));
        if (response == null || response.data() == null || response.data().accessToken() == null) {
            throw new IllegalStateException("服务令牌签发失败：clientId=" + clientId);
        }
        return response.data();
    }
}
