package io.lunar.auth.domain;

import io.lunar.auth.config.ServiceClientProperties;
import io.lunar.auth.domain.dto.IssuedToken;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 服务间身份（客户端凭证，docs/services.md"服务间调用关系"）：
 * 校验 clientId / clientSecret 后签发 SERVICE 令牌，供定时任务、MQ 消费者等无用户上下文的调用方使用，
 * 从而不再"借用"某个用户的令牌。
 *
 * 密钥用常量时间比较，避免时序侧信道；客户端未注册与密钥错误返回同一错误码，防客户端枚举。
 */
@Component
public class ServiceTokenService {

    private final ServiceClientProperties serviceClientProperties;
    private final JwtTokenService jwtTokenService;

    public ServiceTokenService(ServiceClientProperties serviceClientProperties, JwtTokenService jwtTokenService) {
        this.serviceClientProperties = serviceClientProperties;
        this.jwtTokenService = jwtTokenService;
    }

    public IssuedToken issue(String clientId, String clientSecret) {
        String registeredSecret = serviceClientProperties.getSecrets().get(clientId);
        if (registeredSecret == null || clientSecret == null || !constantTimeEquals(registeredSecret, clientSecret)) {
            throw new AuthException.InvalidServiceClient();
        }
        return jwtTokenService.issueServiceToken(clientId,
                Duration.ofMinutes(serviceClientProperties.getTokenTtlMinutes()));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
