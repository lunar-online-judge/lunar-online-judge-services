package io.lunar.anticheat.infra;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 审计服务客户端（尽力而为）：审核等敏感动作写 audit-service 账本。
 * 走 Spring 原生声明式 HTTP 接口（{@link AuditServiceApi}，代理 Bean 见 io.lunar.anticheat.config.ServiceClientConfig），按服务名（audit-service）经 Nacos + LoadBalancer
 * 解析实例，AUDIT_BASE_URL 可覆盖为固定地址排障。
 *
 * 调用失败仅告警不阻断主流程——审计不因服务暂不可用而丢业务动作，缺口由各服务结构化日志兜底。
 * traceId 作为 X-Request-Id 透传，audit-service 用它作为账本 traceId，链路不在服务间断掉。
 */
@Component
public class AuditClient {

    private final AuditServiceApi auditApi;

    public AuditClient(AuditServiceApi auditApi) {
        this.auditApi = auditApi;
    }

    public void record(String action, String targetType, String targetId, String contestId,
                       String detail, String traceId, String operatorToken) {
        auditApi.record(bearer(operatorToken), traceId, Map.of(
                "action", action,
                "targetType", targetType == null ? "" : targetType,
                "targetId", targetId == null ? "" : targetId,
                "contestId", contestId == null ? "" : contestId,
                "detail", detail == null ? "" : detail));
    }

    private static String bearer(String token) {
        String rawToken = token == null ? "" : token.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + rawToken;
    }
}
