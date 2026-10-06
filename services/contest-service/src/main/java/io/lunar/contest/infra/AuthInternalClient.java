package io.lunar.contest.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * auth-service 学生账号内部接口客户端（/api/v1/internal/students）。
 * 转发操作者的 Bearer 令牌（老师/管理员），auth 端校验操作者身份与角色；
 * 服务间独立身份（客户端凭证）落地后替换转发机制。
 */
@Component
public class AuthInternalClient {

    private final RestClient rest;

    public AuthInternalClient(@Value("${AUTH_BASE_URL:http://localhost:8081}") String authBaseUrl) {
        this.rest = RestClient.builder().baseUrl(authBaseUrl).build();
    }

    /** 操作者令牌：入参可能是完整 header（含 Bearer 前缀），统一剥离后由这里重新拼。 */
    private static String bearer(String operatorToken) {
        String raw = operatorToken == null ? "" : operatorToken.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + raw;
    }

    /** 按学号或账户名查学生账号；不存在（404）返回 null，账号可复用时不重复创建。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findStudent(String studentNo, String username, String operatorToken) {
        try {
            return unwrap(rest.get()
                    .uri(uri -> uri.path("/api/v1/internal/students")
                            .queryParam("studentNo", studentNo)
                            .queryParam("username", username)
                            .build())
                    .header("Authorization", bearer(operatorToken))
                    .retrieve()
                    .body(Map.class));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** auth 返回 ApiResponse 信封，这里统一取 data；auth 端 4xx/5xx 原样抛 RestClientResponseException。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrap(Map<String, Object> body) {
        return body == null ? null : (Map<String, Object>) body.get("data");
    }

    /** @return studentId；auth 端错误（409 用户名/学号冲突等）原样抛 RestClientResponseException。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createStudent(String studentNo, String username, String displayName,
                                             String initialPassword, String operatorToken) {
        return unwrap(rest.post()
                .uri("/api/v1/internal/students")
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("studentNo", studentNo, "username", username,
                        "displayName", displayName, "initialPassword", initialPassword))
                .retrieve()
                .body(Map.class));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resetPassword(String studentId, String temporaryPassword, String operatorToken) {
        return unwrap(rest.post()
                .uri("/api/v1/internal/students/{id}/password-reset", studentId)
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("temporaryPassword", temporaryPassword))
                .retrieve()
                .body(Map.class));
    }
}
