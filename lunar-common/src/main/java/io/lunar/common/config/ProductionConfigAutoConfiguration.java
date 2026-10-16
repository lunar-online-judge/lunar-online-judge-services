package io.lunar.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 生产配置门禁自动装配（BACKEND_IMPROVEMENT_PLAN BE-02）：所有服务默认获得通用规则校验；
 * 有服务特有规则的服务（如 auth 的 RSA 私钥与开发种子）自己声明 {@link ProductionConfigGuard} Bean，
 * 这里通过 {@link ConditionalOnMissingBean} 让位。
 *
 * 单例 Bean 在启动期即被创建，因此门禁在应用就绪前完成校验——不通过就启动失败。
 */
@AutoConfiguration
public class ProductionConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProductionConfigGuard productionConfigGuard(Environment environment) {
        return ProductionConfigGuard.forService(environment).verify();
    }
}
