package io.lunar.anticheat.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 审计服务客户端（尽力而为）：审核等敏感动作写 audit-service 账本。
 * 调用失败仅告警不阻断主流程——审计不因服务暂不可用而丢业务动作，
 * 缺口由各服务自身的结构化日志兜底。
 * 寻址：按服务名（audit-service）经 Nacos 解析实例，AUDIT_BASE_URL 可覆盖为固定地址用于排障。
 */
@Component
public class AuditClient {

    private final RestClient rest;

    public AuditClient(RestClient.Builder loadBalancedRestClientBuilder,
                       @Value("${AUDIT_BASE_URL:http://audit-service}") String auditBaseUrl) {
        this.rest = loadBalancedRestClientBuilder.baseUrl(auditBaseUrl).build();
    }

    private static String bearer(String token) {
        String raw = token == null ? "" : token.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + raw;
    }

    public void record(String action, String targetType, String targetId, String contestId,
                       String detail, String traceId, String operatorToken) {
        rest.post()
                .uri("/api/v1/internal/audit/records")
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "action", action,
                        "targetType", targetType == null ? "" : targetType,
                        "targetId", targetId == null ? "" : targetId,
                        "contestId", contestId == null ? "" : contestId,
                        "detail", detail == null ? "" : detail))
                .retrieve()
                .toBodilessEntity();
    }
}
