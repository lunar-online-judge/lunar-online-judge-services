package io.lunar.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一入口网关（docs/services.md gateway-service 节）。
 * 选型：Spring Cloud Gateway（响应式）——需 WebSocket 入口能力与限流/重试生态；
 * 只做路由与透传：JWT 由各资源服务验签，X-Request-Id 由各服务过滤器处理；
 * /api/v1/internal/** 不暴露（无路由即不可达，服务间调用走内网直连）。
 */
@SpringBootApplication
public class GatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
