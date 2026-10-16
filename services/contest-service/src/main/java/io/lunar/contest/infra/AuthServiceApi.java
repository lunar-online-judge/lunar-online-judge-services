package io.lunar.contest.infra;

import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

/**
 * auth-service 学生账号内部接口（/api/v1/internal/students）的声明式 HTTP 接口。
 *
 * 代理 Bean 由 {@code io.lunar.contest.config.ServiceClientConfig} 装配，
 * baseUrl 默认服务名（auth-service，经 Nacos + LoadBalancer 解析实例），AUTH_BASE_URL 可覆盖为固定地址排障。
 */
@HttpExchange("/api/v1/internal/students")
public interface AuthServiceApi {

    @GetExchange
    ApiResponse<Map<String, Object>> findStudent(@RequestParam("studentNo") String studentNo,
                                                 @RequestParam("username") String username,
                                                 @RequestHeader("Authorization") String authorization);

    @PostExchange
    ApiResponse<Map<String, Object>> createStudent(@RequestHeader("Authorization") String authorization,
                                                   @RequestBody Map<String, Object> body);

    @PostExchange("/{studentId}/password-reset")
    ApiResponse<Map<String, Object>> resetPassword(@PathVariable("studentId") String studentId,
                                                   @RequestHeader("Authorization") String authorization,
                                                   @RequestBody Map<String, Object> body);
}
