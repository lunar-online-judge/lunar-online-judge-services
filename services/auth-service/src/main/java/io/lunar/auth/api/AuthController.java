package io.lunar.auth.api;

import io.lunar.auth.domain.AuthService;
import io.lunar.auth.domain.JwksService;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.security.Role;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwksService jwksService;

    public AuthController(AuthService authService, JwksService jwksService) {
        this.authService = authService;
        this.jwksService = jwksService;
    }

    /** 契约见 docs/api.md"登录"；requestId 由 RequestIdFilter 包装后恒可读。 */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        LoginResponse body = new LoginResponse(result.userId(), result.accessToken(), "Bearer",
                result.expiresAt(), result.role(), result.passwordChangeRequired());
        return ResponseEntity.ok(ApiResponse.success(requestId, body));
    }

    /**
     * 标准 JWKS 端点：顶层必须是 {"keys":[...]}（RFC 7517），不套 ApiResponse 统一包装——
     * 资源服务的 JwtDecoder 按标准格式解析（docs/api.md 通用约定的唯一例外）。
     */
    @GetMapping("/jwks")
    public Map<String, Object> jwks() {
        return jwksService.asJson();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String userId, String accessToken, String tokenType, Instant expiresAt,
                                Role role, boolean passwordChangeRequired) {}
}
