package io.lunar.contest.domain;

import io.lunar.contest.infra.AuthInternalClient;
import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnrollmentServiceTest {

    private final CandidateMapper candidateMapper = mock(CandidateMapper.class);
    private final ContestMapper contestMapper = mock(ContestMapper.class);
    private final AuthInternalClient authClient = mock(AuthInternalClient.class);
    private final EnrollmentService service = new EnrollmentService(candidateMapper,
            new ContestService(contestMapper), authClient);

    @BeforeAll
    static void initMpMetadata() {
        // LambdaQueryWrapper 依赖 MP 的实体元数据缓存；纯单测需手动初始化
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                io.lunar.contest.infra.CandidateEntity.class);
    }

    @BeforeEach
    void contestExists() {
        ContestEntity contest = new ContestEntity();
        contest.setId("c1");
        contest.setStatus("PUBLISHED");
        when(contestMapper.selectById("c1")).thenReturn(contest);
    }

    private static CandidateEntity candidate(String studentId, String studentNo, String status) {
        CandidateEntity c = new CandidateEntity();
        c.setId("rel_1");
        c.setContestId("c1");
        c.setStudentId(studentId);
        c.setStudentNoSnapshot(studentNo);
        c.setUsernameSnapshot("student_" + studentNo);
        c.setDisplayNameSnapshot("学生" + studentNo);
        c.setStatus(status);
        c.setEnrolledAt(Instant.now());
        return c;
    }

    @Test
    void addCreatesAccountAndEnrolls() {
        when(candidateMapper.selectOne(any())).thenReturn(null);
        when(authClient.findStudent("20260001", "student_20260001", "tok")).thenReturn(null);
        when(authClient.createStudent("20260001", "student_20260001", "张三", "init-pass-123", "tok"))
                .thenReturn(Map.of("studentId", "stu_1", "username", "student_20260001"));

        var view = service.add("c1", "20260001", "student_20260001", "张三", "init-pass-123", "tok", "teacher_1");

        assertThat(view.studentId()).isEqualTo("stu_1");
        assertThat(view.enrollmentStatus()).isEqualTo("ENROLLED");
        ArgumentCaptor<CandidateEntity> captor = ArgumentCaptor.forClass(CandidateEntity.class);
        org.mockito.Mockito.verify(candidateMapper).insert(captor.capture());
        assertThat(captor.getValue().getStudentNoSnapshot()).isEqualTo("20260001");
        assertThat(captor.getValue().getAddedByTeacherId()).isEqualTo("teacher_1");
    }

    @Test
    void addReusesExistingAuthAccount() {
        when(candidateMapper.selectOne(any())).thenReturn(null);
        when(authClient.findStudent("20260001", "student_20260001", "tok"))
                .thenReturn(Map.of("studentId", "stu_exists", "username", "student_20260001",
                        "displayName", "老账号"));

        var view = service.add("c1", "20260001", "student_20260001", "张三", "init-pass-123", "tok", "teacher_1");

        assertThat(view.studentId()).isEqualTo("stu_exists");
        org.mockito.Mockito.verify(authClient, org.mockito.Mockito.never()).createStudent(any(), any(), any(), any(), any());
    }

    @Test
    void duplicateActiveEnrollmentRejected() {
        when(candidateMapper.selectOne(any())).thenReturn(candidate("stu_1", "20260001", "ENROLLED"));

        assertThatThrownBy(() -> service.add("c1", "20260001", "student_20260001", "张三",
                "init-pass-123", "tok", "teacher_1"))
                .isInstanceOf(ContestException.AlreadyEnrolled.class);
    }

    @Test
    void removedStudentIsReactivated() {
        // 第一次 selectOne（按学号查）返回 REMOVED 行 → 不拒绝；第二次（按 studentId 查）返回同一行
        CandidateEntity removed = candidate("stu_1", "20260001", "REMOVED");
        when(candidateMapper.selectOne(any())).thenReturn(removed, removed);
        when(authClient.findStudent("20260001", "student_20260001", "tok"))
                .thenReturn(Map.of("studentId", "stu_1", "username", "student_20260001"));

        var view = service.add("c1", "20260001", "student_20260001", "张三", "init-pass-123", "tok", "teacher_1");

        assertThat(view.enrollmentStatus()).isEqualTo("ENROLLED");
        org.mockito.Mockito.verify(candidateMapper).updateById(removed);
    }

    @Test
    void resetPasswordRequiresEnrolledStudent() {
        when(candidateMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.resetPassword("c1", "stu_x", "temp-pass-456", "tok", "teacher_1"))
                .isInstanceOf(ContestException.NotEnrolled.class);
    }

    @Test
    void removeSoftDeletesEnrollment() {
        when(candidateMapper.selectOne(any())).thenReturn(candidate("stu_1", "20260001", "ENROLLED"));

        service.remove("c1", "stu_1", "teacher_1");

        ArgumentCaptor<CandidateEntity> captor = ArgumentCaptor.forClass(CandidateEntity.class);
        org.mockito.Mockito.verify(candidateMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REMOVED");
        assertThat(captor.getValue().getRemovedAt()).isNotNull();
    }

    @Test
    void removeAlreadyRemovedThrowsNotEnrolled() {
        when(candidateMapper.selectOne(any())).thenReturn(candidate("stu_1", "20260001", "REMOVED"));

        assertThatThrownBy(() -> service.remove("c1", "stu_1", "teacher_1"))
                .isInstanceOf(ContestException.NotEnrolled.class);
    }
}
