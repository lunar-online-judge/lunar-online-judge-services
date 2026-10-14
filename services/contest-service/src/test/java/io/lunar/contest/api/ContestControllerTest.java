package io.lunar.contest.api;

import io.lunar.common.web.WebCommonAutoConfiguration;
import io.lunar.contest.config.SecurityConfig;
import io.lunar.contest.domain.AnnouncementService;
import io.lunar.contest.domain.ContestException;
import io.lunar.contest.domain.ContestService;
import io.lunar.contest.domain.ContestStatus;
import io.lunar.contest.domain.EnrollmentService;
import io.lunar.contest.infra.ContestEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 错误响应断言走 lunar-common 的自动配置（与运行时同一条装配路径），而非手工 import 处理器。 */
@WebMvcTest(ContestController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(WebCommonAutoConfiguration.class)
class ContestControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ContestService contestService;
    @MockitoBean EnrollmentService enrollmentService;
    @MockitoBean AnnouncementService announcementService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final ContestEntity CONTEST = contest("c1", "DRAFT");

    private static ContestEntity contest(String id, String status) {
        ContestEntity contest = new ContestEntity();
        contest.setId(id);
        contest.setName("考试A");
        contest.setStatus(status);
        contest.setStartAt(Instant.parse("2026-11-01T02:00:00Z"));
        contest.setEndAt(Instant.parse("2026-11-01T04:00:00Z"));
        contest.setCreatedBy("user_1");
        contest.setCreatedAt(Instant.now());
        contest.setUpdatedAt(Instant.now());
        return contest;
    }

    @BeforeEach
    void stubDecoder() {
        // 每个 bean 角色/教师/考生由各用例覆盖
        when(jwtDecoder.decode(anyString())).thenReturn(jwt("CANDIDATE"));
    }

    private static Jwt jwt(String... roles) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .subject("user_1")
                .claim("roles", List.of(roles))
                .build();
    }

    private void decoderRoles(String... roles) {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt(roles));
    }

    @Test
    void createWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/api/v1/contests")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"考试A\",\"startAt\":\"2026-11-01T02:00:00Z\","
                                + "\"endAt\":\"2026-11-01T04:00:00Z\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void teacherCanCreateContest() throws Exception {
        decoderRoles("TEACHER");
        when(contestService.create(anyString(), any(), any(Instant.class), any(Instant.class), eq("user_1")))
                .thenReturn(CONTEST);

        mockMvc.perform(post("/api/v1/contests")
                        .header("Authorization", "Bearer token-value")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"考试A\",\"startAt\":\"2026-11-01T02:00:00Z\","
                                + "\"endAt\":\"2026-11-01T04:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.createdBy").value("user_1"));
    }

    @Test
    void candidateCannotCreateContest() throws Exception {
        mockMvc.perform(post("/api/v1/contests")
                        .header("Authorization", "Bearer token-value")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"考试A\",\"startAt\":\"2026-11-01T02:00:00Z\","
                                + "\"endAt\":\"2026-11-01T04:00:00Z\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAdminCanPublish() throws Exception {
        when(contestService.publish("c1")).thenReturn(contest("c1", "PUBLISHED"));

        // 老师不行
        decoderRoles("TEACHER");
        mockMvc.perform(post("/api/v1/contests/c1/publish").header("Authorization", "Bearer token-value"))
                .andExpect(status().isForbidden());

        // 管理员可以
        decoderRoles("ADMIN");
        mockMvc.perform(post("/api/v1/contests/c1/publish").header("Authorization", "Bearer token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void unknownContestReturns404WithStableCode() throws Exception {
        decoderRoles("ADMIN");
        when(contestService.get("nope")).thenThrow(new ContestException.NotFound());

        mockMvc.perform(get("/api/v1/contests/nope").header("Authorization", "Bearer token-value"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONTEST_NOT_FOUND"));
    }

    @Test
    void invalidTransitionReturns409WithStableCode() throws Exception {
        decoderRoles("ADMIN");
        when(contestService.submitForReview("c1")).thenThrow(new ContestException.InvalidTransition(
                ContestStatus.PUBLISHED, ContestStatus.REVIEWING));

        mockMvc.perform(post("/api/v1/contests/c1/review").header("Authorization", "Bearer token-value"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTEST_INVALID_TRANSITION"));
    }
}
