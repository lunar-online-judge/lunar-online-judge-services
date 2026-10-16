package io.lunar.auth.domain;

import io.lunar.auth.config.ServiceClientProperties;
import io.lunar.auth.domain.dto.IssuedToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 客户端凭证契约（docs/services.md"服务间调用关系"）：只有注册客户端 + 正确密钥才签发 SERVICE 令牌；
 * 未注册与密钥错误返回同一错误码，避免暴露"哪些客户端存在"。
 */
class ServiceTokenServiceTest {

    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final ServiceTokenService service = new ServiceTokenService(properties(), jwtTokenService);

    private static ServiceClientProperties properties() {
        ServiceClientProperties properties = new ServiceClientProperties();
        properties.setTokenTtlMinutes(10);
        properties.setSecrets(Map.of("contest-service", "s3cret"));
        return properties;
    }

    @Test
    void registeredClientWithCorrectSecretGetsServiceToken() {
        when(jwtTokenService.issueServiceToken(eq("contest-service"), any()))
                .thenReturn(new IssuedToken("token-value", Instant.now().plusSeconds(600)));

        IssuedToken issued = service.issue("contest-service", "s3cret");

        assertThat(issued.accessToken()).isEqualTo("token-value");
        verify(jwtTokenService).issueServiceToken(eq("contest-service"), any());
    }

    @Test
    void wrongSecretIsRejectedWithoutIssuingToken() {
        assertThatThrownBy(() -> service.issue("contest-service", "wrong"))
                .isInstanceOf(AuthException.InvalidServiceClient.class)
                .extracting(exception -> ((AuthException) exception).code())
                .isEqualTo("AUTH_INVALID_SERVICE_CLIENT");
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void unknownClientIsRejectedWithSameCodeAsWrongSecret() {
        assertThatThrownBy(() -> service.issue("unknown-service", "s3cret"))
                .isInstanceOf(AuthException.InvalidServiceClient.class);
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void missingSecretIsRejected() {
        assertThatThrownBy(() -> service.issue("contest-service", null))
                .isInstanceOf(AuthException.InvalidServiceClient.class);
        verifyNoInteractions(jwtTokenService);
    }
}
