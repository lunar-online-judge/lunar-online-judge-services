package io.lunar.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 密钥轮换窗口（BACKEND_IMPROVEMENT_PLAN BE-02）：
 * 换新密钥后，**已退役但仍需验签的公钥**继续随 JWKS 发布，直到用它签发的令牌全部过期，
 * 这样轮换不会把还没到期的旧 token 全部作废（也不会因多实例密钥不一致互相拒绝）。
 *
 * 配置形态：{@code auth.jwt.retired-public-keys.<kid> = <X.509 PEM 公钥>}。
 * 只需公钥（不能用于签发），轮换窗口结束后删除对应条目即可。
 */
@ConfigurationProperties("auth.jwt")
public class JwtKeyProperties {

    /** kid → 已退役公钥（PEM）。默认空：未轮换过的部署无需配置。 */
    private Map<String, String> retiredPublicKeys = new LinkedHashMap<>();

    public Map<String, String> getRetiredPublicKeys() {
        return retiredPublicKeys;
    }

    public void setRetiredPublicKeys(Map<String, String> retiredPublicKeys) {
        this.retiredPublicKeys = retiredPublicKeys;
    }
}
