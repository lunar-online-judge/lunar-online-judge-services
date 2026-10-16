package io.lunar.anticheat.infra;

import io.lunar.common.web.RequestIdFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

/**
 * audit-service 账本写入接口（/api/v1/internal/audit/records）的声明式 HTTP 接口。
 * 代理 Bean 由 {@code io.lunar.anticheat.config.ServiceClientConfig} 装配，baseUrl 默认服务名。
 *
 * X-Request-Id 随调用透传：audit-service 用它作为账本的 traceId，链路不会在服务间断掉。
 */
@HttpExchange("/api/v1/internal/audit/records")
public interface AuditServiceApi {

    @PostExchange
    void record(@RequestHeader("Authorization") String authorization,
                @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
                @RequestBody Map<String, Object> body);
}
