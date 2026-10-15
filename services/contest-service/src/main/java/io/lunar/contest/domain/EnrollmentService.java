package io.lunar.contest.domain;

import io.lunar.contest.domain.dto.CandidatePage;
import io.lunar.contest.domain.dto.CandidateView;
import io.lunar.contest.domain.dto.EnrollmentResult;
import io.lunar.contest.infra.AuthInternalClient;
import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报名关系编排（docs/exam-process.md §9、architecture.md §2.1.1-2.1.2）：
 * 学生账号由 auth-service 创建（本服务转发操作者令牌），报名关系归本服务。
 * 同一 contestId+studentNo 只有一条有效报名关系；移出是软删除；重新加入复用原行置回 ENROLLED。
 * 审计：audit-service 未建成，先以结构化日志落 AUDIT 事件（建成后迁移）。
 */
@Component
public class EnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentService.class);

    private final CandidateMapper candidateMapper;
    private final ContestService contestService;
    private final AuthInternalClient authClient;

    public EnrollmentService(CandidateMapper candidateMapper, ContestService contestService,
                             AuthInternalClient authClient) {
        this.candidateMapper = candidateMapper;
        this.contestService = contestService;
        this.authClient = authClient;
    }

    /** 添加学生：账号不存在则经 auth 创建（或账号已存在则复用），再建立/激活报名关系。 */
    @SuppressWarnings("unchecked")
    public EnrollmentResult add(String contestId, String studentNo, String username, String displayName,
                                String initialPassword, String operatorToken, String operatorId) {
        contestService.get(contestId); // 竞赛必须存在

        // 名单中该学号已有效报名 → 拒绝重复（同一 contestId+studentNo 只能有一条有效报名关系）
        CandidateEntity byStudentNo = candidateMapper.selectOne(new LambdaQueryWrapper<CandidateEntity>()
                .eq(CandidateEntity::getContestId, contestId)
                .eq(CandidateEntity::getStudentNoSnapshot, studentNo)
                .last("limit 1"));
        if (byStudentNo != null && "ENROLLED".equals(byStudentNo.getStatus())) {
            throw new ContestException.AlreadyEnrolled();
        }

        // auth 端账号：已存在（他场竞赛的学生）则复用，不存在则创建——同一账号可参加多场竞赛
        Map<String, Object> account = authClient.findStudent(studentNo, username, operatorToken);
        boolean passwordChangeRequired = false;
        String studentId;
        if (account != null) {
            studentId = String.valueOf(account.get("studentId"));
            username = String.valueOf(account.get("username"));
            displayName = String.valueOf(account.get("displayName"));
        } else {
            Map<String, Object> created = authClient.createStudent(studentNo, username, displayName,
                    initialPassword, operatorToken);
            studentId = String.valueOf(created.get("studentId"));
            // 是否需改密以 auth 的响应为准，不在本服务推断
            passwordChangeRequired = Boolean.TRUE.equals(created.get("passwordChangeRequired"));
        }

        // 同一 studentId 的既有关系（含 REMOVED 软删除行）→ 复用该行
        CandidateEntity existing = candidateMapper.selectOne(new LambdaQueryWrapper<CandidateEntity>()
                .eq(CandidateEntity::getContestId, contestId)
                .eq(CandidateEntity::getStudentId, studentId)
                .last("limit 1"));
        if (existing != null) {
            if ("ENROLLED".equals(existing.getStatus())) {
                throw new ContestException.AlreadyEnrolled();
            }
            // REMOVED → 重新激活
            existing.setStatus("ENROLLED");
            existing.setStudentNoSnapshot(studentNo);
            existing.setUsernameSnapshot(username);
            existing.setDisplayNameSnapshot(displayName);
            existing.setAddedByTeacherId(operatorId);
            existing.setEnrolledAt(Instant.now());
            existing.setRemovedAt(null);
            candidateMapper.updateById(existing);
            log.info("AUDIT event=STUDENT_REENROLLED contestId={} studentId={} by={}",
                    contestId, studentId, operatorId);
            return toEnrollmentResult(existing, passwordChangeRequired);
        }

        CandidateEntity candidate = new CandidateEntity();
        candidate.setId(UUID.randomUUID().toString());
        candidate.setContestId(contestId);
        candidate.setStudentId(studentId);
        candidate.setStudentNoSnapshot(studentNo);
        candidate.setUsernameSnapshot(username);
        candidate.setDisplayNameSnapshot(displayName);
        candidate.setAddedByTeacherId(operatorId);
        candidate.setStatus("ENROLLED");
        candidate.setEnrolledAt(Instant.now());
        candidateMapper.insert(candidate);
        log.info("AUDIT event=STUDENT_ENROLLED contestId={} studentId={} by={}", contestId, studentId, operatorId);
        return toEnrollmentResult(candidate, passwordChangeRequired);
    }

    /** 分页查询名单；keyword 匹配学号快照。 */
    public CandidatePage list(String contestId, long page, long pageSize, String keyword) {
        LambdaQueryWrapper<CandidateEntity> query = new LambdaQueryWrapper<CandidateEntity>()
                .eq(CandidateEntity::getContestId, contestId)
                .orderByDesc(CandidateEntity::getEnrolledAt);
        if (keyword != null && !keyword.isBlank()) {
            query.like(CandidateEntity::getStudentNoSnapshot, keyword.trim());
        }
        Page<CandidateEntity> result = candidateMapper.selectPage(new Page<>(page, pageSize), query);
        List<CandidateView> items = result.getRecords().stream()
                .map(this::toView)
                .toList();
        return new CandidatePage(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 重置学生密码：学生须在名单中；密码经 auth 端哈希，本服务不经手存储。 */
    public void resetPassword(String contestId, String studentId, String temporaryPassword,
                              String operatorToken, String operatorId) {
        requireEnrolled(contestId, studentId);
        authClient.resetPassword(studentId, temporaryPassword, operatorToken);
        log.info("AUDIT event=STUDENT_PASSWORD_RESET contestId={} studentId={} by={}",
                contestId, studentId, operatorId);
    }

    /** 移出名单：软删除（status=REMOVED），学生账号与历史数据保留。 */
    public void remove(String contestId, String studentId, String operatorId) {
        CandidateEntity candidate = requireEnrolled(contestId, studentId);
        candidate.setStatus("REMOVED");
        candidate.setRemovedAt(Instant.now());
        candidateMapper.updateById(candidate);
        log.info("AUDIT event=STUDENT_REMOVED contestId={} studentId={} by={}", contestId, studentId, operatorId);
    }

    private CandidateEntity requireEnrolled(String contestId, String studentId) {
        CandidateEntity candidate = candidateMapper.selectOne(new LambdaQueryWrapper<CandidateEntity>()
                .eq(CandidateEntity::getContestId, contestId)
                .eq(CandidateEntity::getStudentId, studentId)
                .last("limit 1"));
        if (candidate == null || !"ENROLLED".equals(candidate.getStatus())) {
            throw new ContestException.NotEnrolled();
        }
        return candidate;
    }

    /** 名单视图取快照列（权威值在 auth_schema.users，快照用于报名当时的名单展示与审计）。 */
    private CandidateView toView(CandidateEntity candidate) {
        return new CandidateView(candidate.getStudentId(), candidate.getStudentNoSnapshot(),
                candidate.getUsernameSnapshot(), candidate.getDisplayNameSnapshot(), candidate.getStatus(),
                candidate.getAddedByTeacherId(), candidate.getEnrolledAt());
    }

    /** 添加学生响应视图（docs/api.md"添加学生"）：同样取快照列 + 本次是否下发了待改密的临时密码。 */
    private EnrollmentResult toEnrollmentResult(CandidateEntity candidate, boolean passwordChangeRequired) {
        return new EnrollmentResult(candidate.getStudentId(), candidate.getStudentNoSnapshot(),
                candidate.getUsernameSnapshot(), candidate.getDisplayNameSnapshot(), candidate.getStatus(),
                passwordChangeRequired);
    }
}
