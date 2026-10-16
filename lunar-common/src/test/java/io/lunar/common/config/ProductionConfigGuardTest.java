package io.lunar.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产配置门禁契约（BE-02）：prod 下开发默认值 fail fast、dev 下完全不干预、
 * 报错只给属性名与原因（不把口令/私钥写进日志）。
 */
class ProductionConfigGuardTest {

    private static final String DEV_SECRET = "change-me-db-secret";

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(ProductionConfigGuard.PRODUCTION_PROFILE);
        return environment;
    }

    @Test
    void devProfileSkipsEveryRule() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.flyway.enabled", "false");
        environment.setProperty("spring.datasource.password", DEV_SECRET);

        assertDoesNotThrow(() -> ProductionConfigGuard.forService(environment)
                .requirePresent("auth.jwt.private-key")
                .verify());
    }

    @Test
    void productionProfileRejectsDevDefaults() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.flyway.enabled", "false");
        environment.setProperty("spring.datasource.password", DEV_SECRET);
        environment.setProperty("spring.cloud.nacos.discovery.fail-fast", "false");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ProductionConfigGuard.forService(environment).verify());

        assertTrue(failure.getMessage().contains("spring.flyway.enabled"));
        assertTrue(failure.getMessage().contains("spring.datasource.password"));
        assertTrue(failure.getMessage().contains("spring.cloud.nacos.discovery.fail-fast"));
        // 只报属性名与原因，不回显取值
        assertFalse(failure.getMessage().contains(DEV_SECRET));
    }

    @Test
    void productionProfilePassesWithProductionValues() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.flyway.enabled", "true");
        environment.setProperty("spring.datasource.password", "s3cret-from-vault");
        environment.setProperty("service.identity.client-secret", "s3cret-from-vault");
        environment.setProperty("spring.cloud.nacos.discovery.fail-fast", "true");

        assertDoesNotThrow(() -> ProductionConfigGuard.forService(environment).verify());
    }

    @Test
    void absentPropertyIsSkipped() {
        // judge 没有 DataSource：通用规则里的数据库相关属性缺失应当跳过，而不是误报
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.cloud.nacos.discovery.fail-fast", "true");

        assertDoesNotThrow(() -> ProductionConfigGuard.forService(environment).verify());
    }

    @Test
    void missingRequiredPropertyFailsFast() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.cloud.nacos.discovery.fail-fast", "true");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ProductionConfigGuard.forService(environment)
                        .requirePresent("auth.jwt.private-key")
                        .verify());

        assertTrue(failure.getMessage().contains("auth.jwt.private-key"));
    }

    @Test
    void devSeedMustBeDisabledInProduction() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.flyway.placeholders.dev_seed", "true");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ProductionConfigGuard.forService(environment)
                        .requireFalse("spring.flyway.placeholders.dev_seed")
                        .verify());

        assertTrue(failure.getMessage().contains("spring.flyway.placeholders.dev_seed"));
    }

    /**
     * map 型配置必须逐个键检查：服务客户端密钥注册表每个 clientId 一个键，
     * 漏掉任意一个就等于留了一条"用默认口令换 SERVICE 令牌"的通道（SERVICE 令牌能读内部测试点与源码）。
     */
    @Test
    void devDefaultServiceClientSecretIsRejectedInProduction() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("auth.service-clients.secrets.contest-service", "change-me-contest");
        environment.setProperty("auth.service-clients.secrets.ai-service", "already-rotated");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ProductionConfigGuard.forService(environment)
                        .requireAllPrefixedNotDevDefault("auth.service-clients.secrets",
                                ProductionConfigGuard.DEV_DEFAULT_PREFIX)
                        .verify());

        // 指出是哪个 clientId 仍是默认值，但不回显取值本身
        assertTrue(failure.getMessage().contains("auth.service-clients.secrets.contest-service"));
        assertFalse(failure.getMessage().contains("change-me-contest"));
    }

    @Test
    void rotatedServiceClientSecretsPass() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.flyway.enabled", "true");
        environment.setProperty("spring.datasource.password", "s3cret-from-vault");
        environment.setProperty("service.identity.client-secret", "s3cret-from-vault");
        environment.setProperty("spring.cloud.nacos.discovery.fail-fast", "true");
        environment.setProperty("auth.service-clients.secrets.contest-service", "s3cret-from-vault");

        assertDoesNotThrow(() -> ProductionConfigGuard.forService(environment)
                .requireAllPrefixedNotDevDefault("auth.service-clients.secrets",
                        ProductionConfigGuard.DEV_DEFAULT_PREFIX)
                .verify());
    }
}
