package io.lunar.contest.infra;

import io.lunar.common.api.ApiResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;

/**
 * auth-service 学生账号内部接口适配层（docs/middleware.md"服务发现"）：
 * 走 Spring 原生声明式 HTTP 接口（{@link AuthServiceApi}，代理 Bean 见 io.lunar.contest.config.ServiceClientConfig），
 * 按服务名（auth-service）经 Nacos + LoadBalancer 解析实例，
 * AUTH_BASE_URL 可覆盖为固定地址排障；转发操作者的 Bearer 令牌（老师/管理员），auth 端校验身份与角色。
 *
 * 本层只做两件事：解 auth 的 {@link ApiResponse} 信封、把"查不到（404）"收敛成 null；
 * 其余 4xx/5xx 原样抛 {@link HttpClientErrorException}，由调用方映射为域异常。
 * 服务间独立身份（客户端凭证）落地后替换转发机制。
 */
@Component
public class AuthInternalClient {

    private final AuthServiceApi authApi;

    public AuthInternalClient(AuthServiceApi authApi) {
        this.authApi = authApi;
    }

    /** 按学号或账户名查学生账号；不存在（404）返回 null，账号可复用时不重复创建。 */
    /** 按学生用户 ID 查询（批量加入既有学生时使用）；不存在返回 null。 */
    public Map<String, Object> findStudentById(String studentId, String operatorToken) {
        try {
            return unwrap(authApi.findStudentById(studentId, bearer(operatorToken)));
        } catch (HttpClientErrorException.NotFound exception) {
            return null;
        }
    }

    public Map<String, Object> findStudent(String studentNo, String username, String operatorToken) {
        try {
            return unwrap(authApi.findStudent(blankToEmpty(studentNo), blankToEmpty(username),
                    bearer(operatorToken)));
        } catch (HttpClientErrorException.NotFound exception) {
            return null;
        }
    }

    /** @return 新建学生账号的响应 data（studentId 等）；auth 端错误（409 用户名/学号冲突等）原样抛出，由调用方映射。 */
    public Map<String, Object> createStudent(String studentNo, String username, String displayName,
                                             String className, String initialPassword, String operatorToken) {
        return unwrap(authApi.createStudent(bearer(operatorToken), Map.of(
                "studentNo", studentNo,
                "username", username,
                "displayName", displayName,
                "className", className,
                "initialPassword", initialPassword)));
    }

    /** 重置学生密码：auth 端生成并下发临时密码，本服务不经手密码材料。 */
    public void resetPassword(String studentId, String temporaryPassword, String operatorToken) {
        authApi.resetPassword(studentId, bearer(operatorToken),
                Map.of("temporaryPassword", temporaryPassword));
    }

    /** 操作者令牌：入参可能是完整 header（含 Bearer 前缀），统一剥离后由这里重新拼。 */
    private static String bearer(String operatorToken) {
        String rawToken = operatorToken == null ? "" : operatorToken.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + rawToken;
    }

    /** 查询参数缺省用空串：auth 端两个参数至少给一个，空串等价于"未提供"。 */
    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> unwrap(ApiResponse<Map<String, Object>> response) {
        return response == null ? null : response.data();
    }
}
