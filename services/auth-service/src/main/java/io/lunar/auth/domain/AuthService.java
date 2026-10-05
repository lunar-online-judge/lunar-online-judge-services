package io.lunar.auth.domain;

import io.lunar.auth.infra.UserEntity;
import io.lunar.auth.infra.UserMapper;
import io.lunar.common.security.Role;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 登录编排：限流检查 → 用户加载 → BCrypt 校验 → 状态校验 → 签发令牌。
 * 用户不存在与密码错误对外不可区分（防枚举），均计入失败限流。
 */
@Component
public class AuthService {

    public record LoginResult(String userId, String accessToken, Instant expiresAt,
                              Role role, boolean passwordChangeRequired) {}

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final LoginRateLimiter rateLimiter;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                       JwtTokenService tokenService, LoginRateLimiter rateLimiter) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
    }

    public LoginResult login(String username, String password) {
        rateLimiter.checkNotLocked(username);

        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimiter.recordFailure(username);
            throw new AuthException.InvalidCredentials();
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AuthException.AccountDisabled();
        }

        rateLimiter.reset(username);
        JwtTokenService.IssuedToken token = tokenService.issue(user.getId(), Role.valueOf(user.getRole()));
        return new LoginResult(user.getId(), token.accessToken(), token.expiresAt(),
                Role.valueOf(user.getRole()), Boolean.TRUE.equals(user.getPasswordChangeRequired()));
    }
}
