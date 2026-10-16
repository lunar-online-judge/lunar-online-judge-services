package io.lunar.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务客户端注册表（docs/services.md"服务间调用关系"、BACKEND_IMPROVEMENT_PLAN BE-01）：
 * clientId（服务名）→ 客户端密钥，供"没有用户上下文"的调用方（定时任务、MQ 消费者）换取 SERVICE 令牌。
 *
 * 密钥属部署期配置：默认值仅供本地联调，生产必须用环境变量注入（见 docs/deployment.md 环境变量表）；
 * 客户端注册后续迁配置中心或库表时，本类改为读注册源即可，调用方不变。
 */
@ConfigurationProperties("auth.service-clients")
public class ServiceClientProperties {

    /** 服务令牌有效期（分钟）：刻意比用户令牌短，泄露窗口更小。 */
    private long tokenTtlMinutes = 10;

    /** 已注册的客户端密钥。 */
    private Map<String, String> secrets = new LinkedHashMap<>();

    public long getTokenTtlMinutes() {
        return tokenTtlMinutes;
    }

    public void setTokenTtlMinutes(long tokenTtlMinutes) {
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }
}
