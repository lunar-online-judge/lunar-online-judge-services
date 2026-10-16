package io.lunar.common.identity;

import io.lunar.common.api.ApiResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 服务令牌契约：命中缓存不重复签发、进入续签余量自动换新、签发失败向调用方抛出。 */
class ServiceTokenProviderTest {

    @Test
    void tokenIsCachedWhileFresh() {
        AtomicInteger issuedCount = new AtomicInteger();
        ServiceTokenApi serviceTokenApi = request -> {
            issuedCount.incrementAndGet();
            return ApiResponse.success("req",
                    response("token-" + issuedCount.get(), Instant.now().plusSeconds(600)));
        };
        ServiceTokenProvider provider = new ServiceTokenProvider(serviceTokenApi, "contest-service", "secret");

        assertEquals("token-1", provider.token());
        assertEquals("token-1", provider.token());
        assertEquals(1, issuedCount.get());
    }

    @Test
    void tokenIsRenewedInsideRefreshMargin() {
        AtomicInteger issuedCount = new AtomicInteger();
        ServiceTokenApi serviceTokenApi = request -> {
            issuedCount.incrementAndGet();
            // 30 秒后过期 → 落在 60 秒续签余量内，每次取都应重新签发
            return ApiResponse.success("req",
                    response("token-" + issuedCount.get(), Instant.now().plusSeconds(30)));
        };
        ServiceTokenProvider provider = new ServiceTokenProvider(serviceTokenApi, "contest-service", "secret");

        assertEquals("token-1", provider.token());
        assertEquals("token-2", provider.token());
        assertEquals(2, issuedCount.get());
    }

    @Test
    void issueFailurePropagatesToCaller() {
        ServiceTokenApi serviceTokenApi = request -> {
            throw new IllegalStateException("auth-service 不可用");
        };
        ServiceTokenProvider provider = new ServiceTokenProvider(serviceTokenApi, "contest-service", "secret");

        assertThrows(IllegalStateException.class, provider::token);
    }

    private static ServiceTokenResponse response(String token, Instant expiresAt) {
        return new ServiceTokenResponse(token, "Bearer", expiresAt, "SERVICE");
    }
}
