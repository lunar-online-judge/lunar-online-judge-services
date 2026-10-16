package io.lunar.contest.domain;

import io.lunar.contest.config.ExamSessionProperties;
import io.lunar.contest.domain.dto.SessionView;
import io.lunar.contest.domain.dto.SubmissionCheck;
import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import io.lunar.contest.infra.SessionEntity;
import io.lunar.contest.infra.SessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 考生会话（docs/exam-process.md §6）：
 * 进入条件 = 名单 ENROLLED + 竞赛状态 PUBLISHED/RUNNING + 服务端时间在时间窗口内；
 * 同一学生同一竞赛最多一个会话——重新登录恢复（断线重连保留剩余时间，不补偿）；
 * 管理端可终止会话（TERMINATED 为终态，重进拒绝）；学生被移出名单时同样使会话失效（§9）；
 * 心跳超时由定时扫描置 EXPIRED，条件更新保证多实例幂等。
 */
@Component
public class ExamSessionService {

    private final SessionMapper sessionMapper;
    private final CandidateMapper candidateMapper;
    private final ContestMapper contestMapper;
    private final ExamSessionProperties properties;

    public ExamSessionService(SessionMapper sessionMapper, CandidateMapper candidateMapper,
                              ContestMapper contestMapper, ExamSessionProperties properties) {
        this.sessionMapper = sessionMapper;
        this.candidateMapper = candidateMapper;
        this.contestMapper = contestMapper;
        this.properties = properties;
    }

    /** 进入/恢复考试会话。 */
    public SessionView enter(String contestId, String studentId) {
        checkWindow(contestId, studentId);

        SessionEntity session = findSession(contestId, studentId);
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

    /**
     * 心跳：仅 ACTIVE 会话；同时复核考试状态、时间窗口与名单——
     * 考试结束（CONTEST_CLOSED）、被移出名单（CONTEST_NOT_ENROLLED）的学生会话立即失效。
     */
    public SessionView heartbeat(String contestId, String studentId) {
        checkWindow(contestId, studentId);
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
        SessionEntity session = findSession(contestId, studentId);
        if (session == null || !"ACTIVE".equals(session.getStatus())) {
            throw new ContestException.SessionNotActive();
        }
        return session;
    }

    /** 服务间提交校验（供 submission-service）：会话 ACTIVE + 竞赛状态/时间窗口 + 名单 ENROLLED。 */
    public SubmissionCheck checkCanSubmit(String contestId, String studentId) {
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new ContestException.NotFound();
        }
        long remaining = 0;
        if (contest.getEndAt() != null) {
            remaining = Math.max(0, contest.getEndAt().getEpochSecond() - Instant.now().getEpochSecond());
        }
        SessionEntity session = findSession(contestId, studentId);
        String sessionStatus = session == null ? "NONE" : session.getStatus();
        boolean inWindow = (contest.getStartAt() == null || !Instant.now().isBefore(contest.getStartAt()))
                && (contest.getEndAt() == null || Instant.now().isBefore(contest.getEndAt()));
        ContestStatus status = ContestStatus.valueOf(contest.getStatus());
        boolean contestOpen = status == ContestStatus.PUBLISHED || status == ContestStatus.RUNNING;
        CandidateEntity candidate = candidateMapper.selectOne(new LambdaQueryWrapper<CandidateEntity>()
                .eq(CandidateEntity::getContestId, contestId)
                .eq(CandidateEntity::getStudentId, studentId)
                .last("limit 1"));
        boolean enrolled = candidate != null && "ENROLLED".equals(candidate.getStatus());
        boolean canSubmit = "ACTIVE".equals(sessionStatus) && inWindow && contestOpen && enrolled;
        return new SubmissionCheck(canSubmit, sessionStatus, contest.getStatus(), remaining);
    }

    /** 心跳超时扫描：ACTIVE 且超过阈值无心跳 → EXPIRED。条件更新保证多实例幂等。 */
    @Scheduled(fixedDelayString = "${exam.session.sweep-interval-ms:30000}")
    public void expireStaleSessions() {
        Instant threshold = Instant.now().minusSeconds(properties.getHeartbeatTimeoutMinutes() * 60);
        LambdaUpdateWrapper<SessionEntity> update = new LambdaUpdateWrapper<SessionEntity>()
                .eq(SessionEntity::getStatus, "ACTIVE")
                .lt(SessionEntity::getLastHeartbeatAt, threshold)
                .set(SessionEntity::getStatus, "EXPIRED")
                .set(SessionEntity::getClosedAt, Instant.now());
        sessionMapper.update(null, update);
    }

    /**
     * 管理端终止考生会话（docs/exam-process.md §6、docs/api.md 会话契约）：
     * ACTIVE/EXPIRED → TERMINATED，终态不可恢复（考生重进被拒）；已是 TERMINATED 时按幂等返回。
     */
    public SessionView terminate(String contestId, String studentId) {
        SessionEntity session = findSession(contestId, studentId);
        if (session == null) {
            throw new ContestException.SessionNotActive();
        }
        if ("TERMINATED".equals(session.getStatus())) {
            return toView(session);
        }
        if (markTerminated(session)) {
            session.setStatus("TERMINATED");
            session.setClosedAt(Instant.now());
            return toView(session);
        }
        // 条件更新落空（并发下状态已被改走）：以库中最新状态为准，不谎报已终止
        SessionEntity current = findSession(contestId, studentId);
        if (current == null) {
            throw new ContestException.SessionNotActive();
        }
        return toView(current);
    }

    /** 学生被移出名单时使会话失效（docs/exam-process.md §9）：无会话或已是终态时静默跳过。 */
    public void terminateIfExists(String contestId, String studentId) {
        SessionEntity session = findSession(contestId, studentId);
        if (session == null || "TERMINATED".equals(session.getStatus())) {
            return;
        }
        markTerminated(session);
    }

    /**
     * 置 TERMINATED 的条件更新：仅当前态仍是我们读到的状态时才写入，返回是否写入成功。
     * 即使写入落空也不会有"可提交的会话"残留——心跳与进入都会重新校验名单。
     */
    private boolean markTerminated(SessionEntity session) {
        LambdaUpdateWrapper<SessionEntity> update = new LambdaUpdateWrapper<SessionEntity>()
                .eq(SessionEntity::getId, session.getId())
                .eq(SessionEntity::getStatus, session.getStatus())
                .set(SessionEntity::getStatus, "TERMINATED")
                .set(SessionEntity::getClosedAt, Instant.now());
        return sessionMapper.update(null, update) > 0;
    }

    private SessionEntity findSession(String contestId, String studentId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<SessionEntity>()
                .eq(SessionEntity::getContestId, contestId)
                .eq(SessionEntity::getStudentId, studentId)
                .last("limit 1"));
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
