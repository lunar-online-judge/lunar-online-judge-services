package io.lunar.contest.infra;

import io.lunar.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 适配层职责：解 auth 的 ApiResponse 信封、把 404 收敛成 null、转发 Bearer 令牌。 */
class AuthInternalClientTest {

    private final AuthServiceApi authApi = mock(AuthServiceApi.class);
    private final AuthInternalClient client = new AuthInternalClient(authApi);

    @Test
    void findStudentUnwrapsApiResponseEnvelope() {
        when(authApi.findStudent("20260001", "student_20260001", "Bearer tok"))
                .thenReturn(ApiResponse.success("req-1", Map.of("studentId", "stu_1")));

        Map<String, Object> found = client.findStudent("20260001", "student_20260001", "tok");

        assertThat(found).containsEntry("studentId", "stu_1");
    }

    @Test
    void findStudentReturnsNullWhenAuthAnswersNotFound() {
        // 用 create 构造真实的 404 异常（它返回具体子类 NotFound），比 mock 框架异常更贴近运行时
        when(authApi.findStudent(any(), any(), any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null));

        assertThat(client.findStudent("20260001", "student_20260001", "tok")).isNull();
    }

    @Test
    void createStudentForwardsBearerTokenAndPayload() {
        when(authApi.createStudent(eq("Bearer tok"), any()))
                .thenReturn(ApiResponse.success("req-2", Map.of("studentId", "stu_new")));

        Map<String, Object> created = client.createStudent("20260001", "student_20260001", "张三",
                "2026级1班", "init-pass-123", "Bearer tok");

        assertThat(created).containsEntry("studentId", "stu_new");
        verify(authApi).createStudent("Bearer tok", Map.of(
                "studentNo", "20260001",
                "username", "student_20260001",
                "displayName", "张三",
                "className", "2026级1班",
                "initialPassword", "init-pass-123"));
    }
}
