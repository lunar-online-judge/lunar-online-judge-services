package io.lunar.common.identity;

import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * auth-service 服务令牌签发接口（{@code POST /api/v1/internal/service-tokens}）的声明式 HTTP 接口。
 *
 * 与审计写入同理：契约被多个服务（contest / submission / judge …）共用，因此放在 lunar-common，
 * 由各服务自己的 {@code ServiceClientConfig} 装配 Bean。端点不经网关（gateway 无
 * {@code /api/v1/internal/**} 路由），只能内网直连；它是"没有用户上下文"的调用方
 * （定时任务、MQ 消费者）取得身份的入口。
 */
@HttpExchange("/api/v1/internal/service-tokens")
public interface ServiceTokenApi {

    @PostExchange
    ApiResponse<ServiceTokenResponse> issue(@RequestBody ServiceTokenRequest request);
}
