package io.lunar.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 生产配置门禁（BACKEND_IMPROVEMENT_PLAN BE-02）：激活 {@link #PRODUCTION_PROFILE} 时校验一批
 * "绝不能是开发默认值"的配置，不通过就**启动失败**（fail fast），而不是带着开发默认上线。
 *
 * 本地 dev/test 不激活该 profile，行为完全不变（BE-02 的兼容要求）；
 * 校验消息只含属性名与原因，**不含属性值**——避免把私钥、口令写进日志。
 *
 * 约定：属性缺失视为"该服务没有这项配置"而跳过（例如 judge 没有 DataSource），
 * 只要属性存在就必须满足规则——否则一个漏配就会静默放过。
 */
public class ProductionConfigGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigGuard.class);

    /** 生产 profile 名：显式激活（SPRING_PROFILES_ACTIVE=prod）才启用门禁。 */
    public static final String PRODUCTION_PROFILE = "prod";

    /** 开发默认值的统一前缀（compose 与 application.yml 里的 change-me*）。 */
    public static final String DEV_DEFAULT_PREFIX = "change-me";

    private final Environment environment;
    private final List<String> violations = new ArrayList<>();

    public ProductionConfigGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * 各服务通用规则：迁移必须开启（schema 不匹配由 Flyway 校验拒绝启动）、
     * 数据库口令与服务客户端密钥不得是开发默认、配置中心必须 fail-fast（否则静默用本地默认配置）。
     */
    public static ProductionConfigGuard forService(Environment environment) {
        return new ProductionConfigGuard(environment)
                .requireTrue("spring.flyway.enabled")
                .requireNotPrefixed("spring.datasource.password", DEV_DEFAULT_PREFIX)
                .requireNotPrefixed("service.identity.client-secret", DEV_DEFAULT_PREFIX)
                .requireTrue("spring.cloud.nacos.discovery.fail-fast");
    }

    /** 属性必须存在且非空（如生产必须外部注入的 RSA 私钥）。 */
    public ProductionConfigGuard requirePresent(String propertyName) {
        if (isProduction() && isBlank(environment.getProperty(propertyName))) {
            violations.add(propertyName + " 未配置（生产必须外部注入）");
        }
        return this;
    }

    /** 属性必须为 true（属性缺失则跳过）。 */
    public ProductionConfigGuard requireTrue(String propertyName) {
        if (isProduction() && "false".equalsIgnoreCase(environment.getProperty(propertyName))) {
            violations.add(propertyName + " 必须为 true");
        }
        return this;
    }

    /** 属性必须为 false（属性缺失则跳过；用于关闭开发种子等）。 */
    public ProductionConfigGuard requireFalse(String propertyName) {
        if (isProduction() && "true".equalsIgnoreCase(environment.getProperty(propertyName))) {
            violations.add(propertyName + " 必须为 false");
        }
        return this;
    }

    /** 属性不得以开发默认前缀开头（属性缺失则跳过；只报告前缀，不报告取值）。 */
    public ProductionConfigGuard requireNotPrefixed(String propertyName, String devDefaultPrefix) {
        String value = environment.getProperty(propertyName);
        if (isProduction() && value != null && value.startsWith(devDefaultPrefix)) {
            violations.add(propertyName + " 仍是开发默认值（" + devDefaultPrefix + "*）");
        }
        return this;
    }

    /** 属性不得等于任一开发默认值（属性缺失则跳过；只报告命中了开发默认，不报告取值）。 */
    public ProductionConfigGuard requireNotOneOf(String propertyName, String... devDefaults) {
        String value = environment.getProperty(propertyName);
        if (isProduction() && value != null && Arrays.asList(devDefaults).contains(value)) {
            violations.add(propertyName + " 仍是开发默认值");
        }
        return this;
    }

    /** 校验并返回自身；不通过则抛异常让应用启动失败。 */
    public ProductionConfigGuard verify() {
        if (!isProduction()) {
            log.debug("未激活 {} profile，跳过生产配置门禁（本地开发便利配置不受影响）", PRODUCTION_PROFILE);
            return this;
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("生产配置门禁未通过（profile=" + PRODUCTION_PROFILE + "）："
                    + String.join("；", violations));
        }
        log.info("生产配置门禁通过（profile={}）", PRODUCTION_PROFILE);
        return this;
    }

    private boolean isProduction() {
        return environment.matchesProfiles(PRODUCTION_PROFILE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
