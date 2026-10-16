package io.lunar.auth.api;

import io.lunar.auth.domain.ServiceTokenService;
import io.lunar.auth.domain.dto.IssuedToken;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.identity.ServiceTokenRequest;
import io.lunar.common.identity.ServiceTokenResponse;
import io.lunar.common.security.Role;
import io.lunar.common.web.RequestIdFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间身份入口（docs/services.md"服务间调用关系"）：{@code POST /api/v1/internal/service-tokens}。
 *
 * 刻意不加 {@code @PreAuthorize}——调用方此刻还没有令牌，凭证就是 clientId/clientSecret；
 * 端点不经网关（gateway 无 {@code /api/v1/internal/**} 路由），只能内网直连。
 * 响应与登录一致（tokenType=Bearer，role=SERVICE），expiresAt 与令牌 exp 声明一致。
 */
@RestController
public class ServiceTokenController {

    private final ServiceTokenService serviceTokenService;

    public ServiceTokenController(ServiceTokenService serviceTokenService) {
        this.serviceTokenService = serviceTokenService;
    }

    @PostMapping("/api/v1/internal/service-tokens")
    public ResponseEntity<ApiResponse<ServiceTokenResponse>> issue(
            @RequestBody ServiceTokenRequest request,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        IssuedToken token = serviceTokenService.issue(request.clientId(), request.clientSecret());
        return ResponseEntity.ok(ApiResponse.success(requestId,
                new ServiceTokenResponse(token.accessToken(), "Bearer", token.expiresAt(), Role.SERVICE.name())));
    }
}
