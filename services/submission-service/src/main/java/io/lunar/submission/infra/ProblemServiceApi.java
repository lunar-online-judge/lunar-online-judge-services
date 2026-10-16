package io.lunar.submission.infra;

import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Map;

/**
 * problem-service 题目当前发布版本（考生视角，不含测试点）的声明式 HTTP 接口。
 * 代理 Bean 由 {@code io.lunar.submission.config.ServiceClientConfig} 装配，baseUrl 默认服务名。
 */
@HttpExchange("/api/v1/problems")
public interface ProblemServiceApi {

    @GetExchange("/{problemId}")
    ApiResponse<Map<String, Object>> currentVersion(@PathVariable("problemId") String problemId,
                                                    @RequestHeader("Authorization") String authorization);
}
