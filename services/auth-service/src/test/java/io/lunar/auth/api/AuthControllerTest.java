package io.lunar.auth.api;

import io.lunar.auth.domain.AuthException;
import io.lunar.auth.domain.AuthService;
import io.lunar.auth.domain.JwksService;
import io.lunar.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({io.lunar.auth.config.SecurityConfig.class, io.lunar.auth.api.ApiExceptionHandler.class})
class AuthControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AuthService authService;
    @MockitoBean JwksService jwksService;

    private static final AuthService.LoginResult RESULT = new AuthService.LoginResult(
            "user_1", "jwt-token-value", Instant.parse("2026-10-05T12:30:00Z"), Role.CANDIDATE, true);

    @Test
    void loginSuccessReturnsContractFields() throws Exception {
        when(authService.login("student_20260001", "secret")).thenReturn(RESULT);

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Request-Id", "req-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student_20260001\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-test-1"))
                .andExpect(jsonPath("$.data.userId").value("user_1"))
                .andExpect(jsonPath("$.data.accessToken").value("jwt-token-value"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.role").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void invalidCredentialsReturn401WithStableCode() throws Exception {
        when(authService.login("who", "bad")).thenThrow(new AuthException.InvalidCredentials());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"who\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void disabledAccountReturns403() throws Exception {
        when(authService.login("frozen", "secret")).thenThrow(new AuthException.AccountDisabled());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frozen\",\"password\":\"secret\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_DISABLED"));
    }

    @Test
    void rateLimitedReturns429() throws Exception {
        when(authService.login("spam", "secret")).thenThrow(new AuthException.RateLimited());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"spam\",\"password\":\"secret\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_RATE_LIMITED"));
    }

    @Test
    void blankFieldsReturn400ValidationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void jwksIsPubliclyAccessible() throws Exception {
        when(jwksService.asJson()).thenReturn(Map.of("keys", List.of()));

        mockMvc.perform(get("/api/v1/auth/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").exists());
    }

    @Test
    void otherEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/anything-else"))
                .andExpect(status().isUnauthorized());
    }
}
