package io.lunar.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌失效时间线校验（后续后端优化计划 BE-09 第二部分）。
 *
 * 为什么需要：JWT 是离线校验的，禁用账号或重置密码**不会**让已签发的令牌失效——旧令牌在到期前照样能用。
 * auth 为每个用户维护"此时间之前签发的令牌一律无效"（`tokens_valid_after`），这里拿令牌的 `iat` 与它比较。
 *
 * 取舍：
 * - **本地缓存**（默认 30 秒）：每个请求都回源 auth 会把它变成单点瓶颈；缓存 TTL 就是失效传播的承诺时限。
 * - **fail-open**：auth 不可达时放行（只打 warn）。可用性优先于"立即吊销"，且这与其他短期状态层
 *   （登录限流、消费去重）的降级策略一致；令牌本身仍有签名与 exp 兜底。
 */
public class TokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationValidator.class);

    private final RestClient restClient;
    private final String uriTemplate;
    private final Duration cacheTtl;

    /** 用户 -> 失效时间线（null 表示没有失效点）。 */
    private final Map<String, Instant> valueCache = new ConcurrentHashMap<>();

    /** 用户 -> 上面这个值可以继续用到什么时候。 */
    private final Map<String, Instant> expiryCache = new ConcurrentHashMap<>();

    public TokenRevocationValidator(RestClient restClient, String uriTemplate, Duration cacheTtl) {
        this.restClient = restClient;
        this.uriTemplate = uriTemplate;
        this.cacheTtl = cacheTtl;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String userId = token.getSubject();
        Instant issuedAt = token.getIssuedAt();
        if (userId == null || issuedAt == null) {
            // 没有 sub/iat 的令牌交给其它校验器判断（这里不做额外约束）
            return OAuth2TokenValidatorResult.success();
        }
        Instant validAfter = tokensValidAfter(userId);
        if (validAfter != null && issuedAt.isBefore(validAfter)) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "令牌已失效（账号被禁用或密码被重置）", null));
        }
        return OAuth2TokenValidatorResult.success();
    }

    /** 查询（带缓存）某用户的失效时间线；查不到或回源失败都返回 null（按"没有失效点"处理）。 */
    private Instant tokensValidAfter(String userId) {
        Instant expiresAt = expiryCache.get(userId);
        if (expiresAt != null && Instant.now().isBefore(expiresAt)) {
            return valueCache.get(userId);
        }
        try {
            Map<?, ?> body = restClient.get().uri(uriTemplate, userId).retrieve().body(Map.class);
            Instant validAfter = extract(body);
            valueCache.put(userId, validAfter);
            expiryCache.put(userId, Instant.now().plus(cacheTtl));
            return validAfter;
        } catch (RuntimeException exception) {
            log.warn("查询令牌失效时间线失败，按未失效继续（fail-open）：userId={}", userId, exception);
            return null;
        }
    }

    private static Instant extract(Map<?, ?> body) {
        if (body == null) {
            return null;
        }
        Object data = body.get("data");
        Object value = data instanceof Map<?, ?> dataMap ? dataMap.get("tokensValidAfter") : null;
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Instant.parse(value.toString());
    }
}
