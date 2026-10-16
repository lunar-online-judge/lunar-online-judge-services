package io.lunar.common.audit;

import io.lunar.common.web.RequestIdFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * audit-service 账本写入接口（{@code POST /api/v1/internal/audit/records}）的声明式 HTTP 接口。
 *
 * 与只服务单一调用方的 {@code AuthServiceApi} / {@code ContestServiceApi} / {@code ProblemServiceApi} 不同，
 * 这个契约被四个服务（contest / auth / submission / anti-cheat）**完全相同**地使用，因此放在 lunar-common 共享，
 * 由各服务自己的 {@code ServiceClientConfig} 经 {@code HttpServiceClientFactory} 装配成 Bean
 * （baseUrl 默认服务名，{@code AUDIT_BASE_URL} 可覆盖为固定地址排障）。
 *
 * 端点不经网关（gateway 无 {@code /api/v1/internal/**} 路由），只能内网直连；
 * X-Request-Id 随调用透传，audit-service 用它作为账本 traceId，链路不在服务间断掉。
 */
@HttpExchange("/api/v1/internal/audit/records")
public interface AuditServiceApi {

    @PostExchange
    void record(@RequestHeader("Authorization") String authorization,
                @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
                @RequestBody AuditRecordCommand command);
}
