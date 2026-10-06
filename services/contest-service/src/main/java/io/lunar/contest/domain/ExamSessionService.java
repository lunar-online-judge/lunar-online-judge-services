package io.lunar.contest.domain;

import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import io.lunar.contest.infra.SessionEntity;
import io.lunar.contest.infra.SessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 考生会话（docs/exam-process.md §6）：
 * 进入条件 = 名单 ENROLLED + 竞赛状态 PUBLISHED/RUNNING + 服务端时间在时间窗口内；
 * 同一学生同一竞赛最多一个会话——重新登录恢复（断线重连保留剩余时间，不补偿）；
 * 管理端终止（TERMINATED）为终态，重进拒绝（v1 决策，api.md 已标注）；
 * 心跳超时由定时扫描置 EXPIRED，条件更新保证多实例幂等。
 */
@Component
public class ExamSessionService {

    public record SessionView(String sessionId, String status, Instant startedAt, long remainingSeconds) {}

    private final SessionMapper sessionMapper;
    private final CandidateMapper candidateMapper;
    private final ContestMapper contestMapper;
    private final long heartbeatTimeoutMinutes;

    public ExamSessionService(SessionMapper sessionMapper, CandidateMapper candidateMapper,
                              ContestMapper contestMapper,
                              @Value("${exam.session.heartbeat-timeout-minutes:2}") long heartbeatTimeoutMinutes) {
        this.sessionMapper = sessionMapper;
        this.candidateMapper = candidateMapper;
        this.contestMapper = contestMapper;
        this.heartbeatTimeoutMinutes = heartbeatTimeoutMinutes;
    }

    /** 进入/恢复考试会话。 */
    public SessionView enter(String contestId, String studentId) {
        checkWindow(contestId, studentId);

        SessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<SessionEntity>()
                .eq(SessionEntity::getContestId, contestId)
                .eq(SessionEntity::getStudentId, studentId)
                .last("limit 1"));

        if (session == null) {
            session = new SessionEntity();
            session.setId(UUID.randomUUID().toString());
            session.setContestId(contestId);
            session.setStudentId(studentId);
            session.setStatus("ACTIVE");
            session.setStartedAt(Instant.now());
            session.setLastHeartbeatAt(Instant.now());
            sessionMapper.insert(session);
        } else if ("EXPIRED".equals(session.getStatus())) {
            // 断线重连：过期会话恢复为 ACTIVE（剩余时间按 endAt 计算，不补偿）
            session.setStatus("ACTIVE");
            session.setLastHeartbeatAt(Instant.now());
            session.setClosedAt(null);
            sessionMapper.updateById(session);
        } else if ("TERMINATED".equals(session.getStatus())) {
            throw new ContestException.SessionNotActive();
        } else {
            // ACTIVE：幂等恢复，顺带刷新心跳
            session.setLastHeartbeatAt(Instant.now());
            sessionMapper.updateById(session);
        }
        return toView(session);
    }

    /** 心跳：仅 ACTIVE 会话；刷新最后心跳并返回剩余秒数。 */
    public SessionView heartbeat(String contestId, String studentId) {
        SessionEntity session = requireActive(contestId, studentId);
        session.setLastHeartbeatAt(Instant.now());
        sessionMapper.updateById(session);
        return toView(session);
    }

    private void checkWindow(String contestId, String studentId) {
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new ContestException.NotFound();
        }
        CandidateEntity candidate = candidateMapper.selectOne(new LambdaQueryWrapper<CandidateEntity>()
                .eq(CandidateEntity::getContestId, contestId)
                .eq(CandidateEntity::getStudentId, studentId)
                .last("limit 1"));
        if (candidate == null || !"ENROLLED".equals(candidate.getStatus())) {
            throw new ContestException.NotEnrolled();
        }
        ContestStatus status = ContestStatus.valueOf(contest.getStatus());
        if (status != ContestStatus.PUBLISHED && status != ContestStatus.RUNNING) {
            throw new ContestException.NotRunning();
        }
        Instant now = Instant.now();
        if (contest.getStartAt() != null && now.isBefore(contest.getStartAt())) {
            throw new ContestException.NotRunning(); // 未到开始时间
        }
        if (contest.getEndAt() != null && !now.isBefore(contest.getEndAt())) {
            throw new ContestException.Closed(); // 已结束
        }
    }

    private SessionEntity requireActive(String contestId, String studentId) {
        SessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<SessionEntity>()
                .eq(SessionEntity::getContestId, contestId)
                .eq(SessionEntity::getStudentId, studentId)
                .last("limit 1"));
        if (session == null || !"ACTIVE".equals(session.getStatus())) {
            throw new ContestException.SessionNotActive();
        }
        return session;
    }

    /** 心跳超时扫描：ACTIVE 且超过阈值无心跳 → EXPIRED。条件更新保证多实例幂等。 */
    @Scheduled(fixedDelayString = "${exam.session.sweep-interval-ms:30000}")
    public void expireStaleSessions() {
        Instant threshold = Instant.now().minusSeconds(heartbeatTimeoutMinutes * 60);
        LambdaUpdateWrapper<SessionEntity> update = new LambdaUpdateWrapper<SessionEntity>()
                .eq(SessionEntity::getStatus, "ACTIVE")
                .lt(SessionEntity::getLastHeartbeatAt, threshold)
                .set(SessionEntity::getStatus, "EXPIRED")
                .set(SessionEntity::getClosedAt, Instant.now());
        sessionMapper.update(null, update);
    }

    private SessionView toView(SessionEntity session) {
        ContestEntity contest = contestMapper.selectById(session.getContestId());
        long remaining = 0;
        if (contest != null && contest.getEndAt() != null) {
            remaining = Math.max(0, contest.getEndAt().getEpochSecond() - Instant.now().getEpochSecond());
        }
        return new SessionView(session.getId(), session.getStatus(), session.getStartedAt(), remaining);
    }
}
