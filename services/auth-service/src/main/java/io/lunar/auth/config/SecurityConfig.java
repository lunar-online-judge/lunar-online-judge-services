package io.lunar.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全基线（development-guide 第 5 节第 3 步）：放行登录与 JWKS，其余一律认证；
 * 无状态会话，资源服务鉴权由各服务的 OAuth2 资源服务器配置承担。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auth/jwks").permitAll()
                        // Swagger/OpenAPI 文档（本地调试用；生产环境需收紧或关闭）
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                // 未认证返回 401（Bearer 语义），而非 Security 默认的 403
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint()));
        return http.build();
    }
}
