package io.lunar.judge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * 安全基线：judge-service 尚未接入令牌验签（docs/services.md），此前依赖 Spring Security 的默认行为
 * （类路径有 spring-security 即全部端点 401）。这里把该行为显式化，并额外放行健康检查端点——
 * 否则 actuator 也会被默认策略挡在 401 之外，编排与运维无法探测。
 *
 * 保持 401 语义（而非默认回退到 403）：显式指定 HttpStatusEntryPoint。
 * 接入令牌验签（资源服务器 + JWKS）应随判题链路后续一并完成。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 健康检查（actuator 只暴露 health）：供编排与运维探测，不展示细节
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
