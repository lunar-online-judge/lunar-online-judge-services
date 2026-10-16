package io.lunar.submission.infra;

import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Map;

/**
 * contest-service 提交判定内部接口（/api/v1/internal/contests/{contestId}/session）的声明式 HTTP 接口。
 * 代理 Bean 由 {@code io.lunar.submission.config.ServiceClientConfig} 装配，baseUrl 默认服务名。
 */
@HttpExchange("/api/v1/internal/contests")
public interface ContestServiceApi {

    @GetExchange("/{contestId}/session")
    ApiResponse<Map<String, Object>> checkSession(@PathVariable("contestId") String contestId,
                                                  @RequestHeader("Authorization") String authorization);
}
