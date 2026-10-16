package io.lunar.submission.infra;

import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;
import java.util.Map;

/**
 * problem-service 服务间测试点接口（{@code /api/v1/internal/problems/...}）的声明式 HTTP 接口。
 * 代理 Bean 由 {@code io.lunar.submission.config.ServiceClientConfig} 装配，baseUrl 默认服务名，
 * PROBLEM_BASE_URL 可覆盖为固定地址排障。
 *
 * 该端点不经网关（gateway 无 {@code /api/v1/internal/**} 路由），且**只接受服务身份令牌**
 * （problem 侧要求 `roles=["SERVICE"]`）——测试数据不能靠"内网 + 考生令牌"取走。
 */
@HttpExchange("/api/v1/internal/problems")
public interface ProblemInternalApi {

    @GetExchange("/{problemId}/versions/{versionId}/testcases")
    ApiResponse<List<Map<String, Object>>> testcases(@PathVariable("problemId") String problemId,
                                                     @PathVariable("versionId") String versionId,
                                                     @RequestHeader("Authorization") String authorization);
}
