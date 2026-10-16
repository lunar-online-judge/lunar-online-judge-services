package io.lunar.auth.config;

import io.lunar.common.config.ProductionConfigGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * auth-service 的生产配置门禁（BACKEND_IMPROVEMENT_PLAN BE-02）：在通用规则之上追加
 * "生产必须有持久 RSA 私钥、必须关闭开发种子、服务客户端密钥不得是开发默认"。
 *
 * 本 Bean 覆盖 lunar-common 的默认门禁（{@code @ConditionalOnMissingBean}），因此通用规则要一并调用。
 */
@Configuration(proxyBeanMethods = false)
public class ProductionConfigGate {

    @Bean
    public ProductionConfigGuard productionConfigGuard(Environment environment) {
        return ProductionConfigGuard.forService(environment)
                // 缺持久私钥时 AuthKeyConfig 会临时生成开发密钥：多实例互不认、重启令牌全失效
                .requirePresent("auth.jwt.private-key")
                // V3 迁移的开发种子管理员（admin/admin123）绝不能带进生产
                .requireFalse("spring.flyway.placeholders.dev_seed")
                .verify();
    }
}
