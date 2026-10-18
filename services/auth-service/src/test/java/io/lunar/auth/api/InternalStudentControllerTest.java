package io.lunar.auth.api;

import io.lunar.auth.config.SecurityConfig;
import io.lunar.auth.domain.StudentAccountService;
import io.lunar.auth.domain.UserException;
import io.lunar.auth.domain.dto.CreatedStudent;
import io.lunar.common.web.WebCommonAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 错误响应断言走 lunar-common 的自动配置（与运行时同一条装配路径），而非手工 import 处理器。 */
@WebMvcTest(InternalStudentController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(WebCommonAutoConfiguration.class)
class InternalStudentControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean StudentAccountService studentAccountService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final CreatedStudent CREATED =
            new CreatedStudent("stu_1", "20260001", "student_20260001", "张三", null, true);

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt("TEACHER"));
    }

    private static Jwt jwt(String... roles) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .subject("teacher_1")
                .claim("roles", List.of(roles))
                .build();
    }

    @Test
    void teacherCreatesStudent() throws Exception {
        when(studentAccountService.createStudent(eq("20260001"), eq("student_20260001"),
                eq("张三"), eq(null), eq("initial-pass-123"), eq("teacher_1"), eq("Bearer token-value")))
                .thenReturn(CREATED);

        mockMvc.perform(post("/api/v1/internal/students")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"20260001\",\"username\":\"student_20260001\","
                                + "\"displayName\":\"张三\",\"initialPassword\":\"initial-pass-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value("stu_1"))
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.data.username").value("student_20260001"));
    }

    @Test
    void candidateRoleCannotCreateStudent() throws Exception {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt("CANDIDATE"));

        mockMvc.perform(post("/api/v1/internal/students")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"20260001\",\"username\":\"student_20260001\","
                                + "\"displayName\":\"张三\",\"initialPassword\":\"initial-pass-123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateUsernameReturns409() throws Exception {
        when(studentAccountService.createStudent(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new UserException.UsernameExists());

        mockMvc.perform(post("/api/v1/internal/students")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"20260002\",\"username\":\"student_20260001\","
                                + "\"displayName\":\"李四\",\"initialPassword\":\"initial-pass-123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_USERNAME_EXISTS"));
    }

    @Test
    void weakPasswordReturns400() throws Exception {
        when(studentAccountService.createStudent(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new UserException.WeakPassword());

        mockMvc.perform(post("/api/v1/internal/students")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"20260003\",\"username\":\"student_20260003\","
                                + "\"displayName\":\"王五\",\"initialPassword\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_WEAK_PASSWORD"));
    }

    @Test
    void findStudentByStudentNo() throws Exception {
        when(studentAccountService.findStudent("20260001", null)).thenReturn(CREATED);

        mockMvc.perform(get("/api/v1/internal/students").param("studentNo", "20260001")
                        .header("Authorization", "Bearer token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value("stu_1"));
    }

    @Test
    void findMissingStudentReturns404() throws Exception {
        when(studentAccountService.findStudent("99999999", null)).thenReturn(null);

        mockMvc.perform(get("/api/v1/internal/students").param("studentNo", "99999999")
                        .header("Authorization", "Bearer token-value"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AUTH_USER_NOT_FOUND"));
    }

    @Test
    void resetPasswordReturnsPasswordChangeRequired() throws Exception {
        Mockito.doNothing()
                .when(studentAccountService).resetStudentPassword(eq("stu_1"), eq("temp-pass-456"),
                        eq("Bearer token-value"));

        mockMvc.perform(post("/api/v1/internal/students/stu_1/password-reset")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"temp-pass-456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(true));
    }
}
