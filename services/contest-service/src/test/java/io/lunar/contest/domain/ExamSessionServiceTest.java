package io.lunar.contest.domain;

import io.lunar.contest.config.ExamSessionProperties;
import io.lunar.contest.domain.dto.SessionView;
import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import io.lunar.contest.infra.SessionEntity;
import io.lunar.contest.infra.SessionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExamSessionServiceTest {

    private final SessionMapper sessionMapper = mock(SessionMapper.class);
    private final CandidateMapper candidateMapper = mock(CandidateMapper.class);
    private final ContestMapper contestMapper = mock(ContestMapper.class);
    private final ExamSessionService service = new ExamSessionService(
            sessionMapper, candidateMapper, contestMapper, sessionProperties());

    private static ExamSessionProperties sessionProperties() {
        ExamSessionProperties properties = new ExamSessionProperties();
        properties.setHeartbeatTimeoutMinutes(2);
        return properties;
    }

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SessionEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CandidateEntity.class);
    }

    private static final Instant PAST = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        // 默认：已发布、时间窗口进行中、学生已报名
        ContestEntity contest = new ContestEntity();
        contest.setId("c1");
        contest.setStatus("PUBLISHED");
        contest.setStartAt(PAST);
        contest.setEndAt(FUTURE);
        when(contestMapper.selectById("c1")).thenReturn(contest);

        CandidateEntity candidate = new CandidateEntity();
        candidate.setContestId("c1");
        candidate.setStudentId("stu_1");
        candidate.setStatus("ENROLLED");
        when(candidateMapper.selectOne(any())).thenReturn(candidate);

        when(sessionMapper.selectOne(any())).thenReturn(null);
    }

    private static SessionEntity session(String id, String status) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setContestId("c1");
        session.setStudentId("stu_1");
        session.setStatus(status);
        session.setStartedAt(PAST);
        session.setLastHeartbeatAt(PAST);
        return session;
    }

    @Test
    void enterCreatesActiveSession() {
        SessionView view = service.enter("c1", "stu_1");

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.remainingSeconds()).isGreaterThan(0);
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getStudentId()).isEqualTo("stu_1");
    }

    @Test
    void enterNotEnrolledRejected() {
        when(candidateMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.enter("c1", "stu_1"))
                .isInstanceOf(ContestException.NotEnrolled.class);
    }

    @Test
    void enterBeforeStartRejected() {
        when(contestMapper.selectById("c1")).thenReturn(contest("PUBLISHED", FUTURE, FUTURE));

        assertThatThrownBy(() -> service.enter("c1", "stu_1"))
                .isInstanceOf(ContestException.NotRunning.class);
    }

    @Test
    void enterAfterEndRejected() {
        when(contestMapper.selectById("c1")).thenReturn(contest("PUBLISHED", PAST, PAST));

        assertThatThrownBy(() -> service.enter("c1", "stu_1"))
                .isInstanceOf(ContestException.Closed.class);
    }

    @Test
    void enterDraftContestRejected() {
        when(contestMapper.selectById("c1")).thenReturn(contest("DRAFT", PAST, FUTURE));

        assertThatThrownBy(() -> service.enter("c1", "stu_1"))
                .isInstanceOf(ContestException.NotRunning.class);
    }

    @Test
    void enterRestoresExpiredSession() {
        when(sessionMapper.selectOne(any())).thenReturn(session("s1", "EXPIRED"));

        SessionView view = service.enter("c1", "stu_1");

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.sessionId()).isEqualTo("s1"); // 同一会话恢复，剩余时间保留
        verify(sessionMapper).updateById(any(SessionEntity.class));
    }

    @Test
    void enterTerminatedSessionRejected() {
        when(sessionMapper.selectOne(any())).thenReturn(session("s1", "TERMINATED"));

        assertThatThrownBy(() -> service.enter("c1", "stu_1"))
                .isInstanceOf(ContestException.SessionNotActive.class);
    }

    @Test
    void enterActiveSessionIsIdempotentHeartbeat() {
        when(sessionMapper.selectOne(any())).thenReturn(session("s1", "ACTIVE"));

        SessionView view = service.enter("c1", "stu_1");

        assertThat(view.sessionId()).isEqualTo("s1");
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getLastHeartbeatAt()).isAfter(PAST);
    }

    @Test
    void heartbeatRequiresActiveSession() {
        when(sessionMapper.selectOne(any())).thenReturn(session("s1", "EXPIRED"));

        assertThatThrownBy(() -> service.heartbeat("c1", "stu_1"))
                .isInstanceOf(ContestException.SessionNotActive.class);
    }

    @Test
    void heartbeatRefreshesActiveSession() {
        when(sessionMapper.selectOne(any())).thenReturn(session("s1", "ACTIVE"));

        SessionView view = service.heartbeat("c1", "stu_1");

        assertThat(view.remainingSeconds()).isGreaterThan(0);
        verify(sessionMapper).updateById(any(SessionEntity.class));
    }

    @Test
    void heartbeatAfterExamEndThrowsClosed() {
        when(contestMapper.selectById("c1")).thenReturn(contest("PUBLISHED", PAST, PAST));
        when(sessionMapper.selectOne(any())).thenReturn(session("s1", "ACTIVE"));

        assertThatThrownBy(() -> service.heartbeat("c1", "stu_1"))
                .isInstanceOf(ContestException.Closed.class);
    }

    @Test
    void heartbeatAfterRemovalThrowsNotEnrolled() {
        when(candidateMapper.selectOne(any())).thenReturn(candidate("stu_1", "20260001", "REMOVED"));
        when(sessionMapper.selectOne(any())).thenReturn(session("s1", "ACTIVE"));

        assertThatThrownBy(() -> service.heartbeat("c1", "stu_1"))
                .isInstanceOf(ContestException.NotEnrolled.class);
    }

    @Test
    void staleSessionSweepExpiresOnlyStale() {
        service.expireStaleSessions();

        // 条件更新（status=ACTIVE 且 last_heartbeat < 阈值）由 SQL 保证；这里验证执行了批量更新
        verify(sessionMapper).update(isNull(), any());
    }

    private static CandidateEntity candidate(String studentId, String studentNo, String status) {
        CandidateEntity candidate = new CandidateEntity();
        candidate.setContestId("c1");
        candidate.setStudentId(studentId);
        candidate.setStudentNoSnapshot(studentNo);
        candidate.setStatus(status);
        return candidate;
    }

    private static ContestEntity contest(String status, Instant startAt, Instant endAt) {
        ContestEntity contest = new ContestEntity();
        contest.setId("c1");
        contest.setStatus(status);
        contest.setStartAt(startAt);
        contest.setEndAt(endAt);
        return contest;
    }
}
