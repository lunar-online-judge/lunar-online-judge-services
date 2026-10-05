package io.lunar.auth.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            @RequestHeader(value = "X-Request-Id", defaultValue = "local") String requestId) {
        Role role = request.username().equalsIgnoreCase("admin") ? Role.ADMIN : Role.CANDIDATE;
        return ApiResponse.success(requestId, new LoginResponse("dev-" + UUID.randomUUID(), "mock-access-token", role, Instant.now().plusSeconds(1800)));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String userId, String accessToken, Role role, Instant expiresAt) {}
}
