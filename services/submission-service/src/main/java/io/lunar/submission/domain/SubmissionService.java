package io.lunar.submission.domain;

import io.lunar.common.judge.JudgeVerdict;
import io.lunar.submission.domain.dto.SubmissionView;
import io.lunar.submission.infra.OutboxEventEntity;
import io.lunar.submission.infra.OutboxEventMapper;
import io.lunar.submission.infra.SubmissionEntity;
import io.lunar.submission.infra.SubmissionMapper;
import io.lunar.submission.infra.UpstreamClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提交流程（docs/architecture.md §8）：
 * 五项校验中已实现三项——会话有效、竞赛进行中（contest 服务间判定）、语言允许（problem 白名单）；
 * 「题目属于竞赛」依赖随机分配关联表、「提交次数未超限」阈值待确认（exam-process §10），标记 TODO 不假装校验。
 * 幂等：Idempotency-Key 同学生唯一；同键同指纹返回首次结果（200），同键不同指纹 409 SUBMISSION_DUPLICATE。
 * 事务：submission(QUEUED) 与 Outbox(submission.created) 同一本地事务落库，由 OutboxPublisher 投递。
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final SubmissionMapper submissionMapper;
    private final OutboxEventMapper outboxMapper;
    private final UpstreamClient upstream;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SubmissionService(SubmissionMapper submissionMapper, OutboxEventMapper outboxMapper,
                             UpstreamClient upstream) {
        this.submissionMapper = submissionMapper;
        this.outboxMapper = outboxMapper;
        this.upstream = upstream;
    }

    @Transactional
    public SubmissionView submit(String contestId, String problemId, String language, String sourceCode,
                                 String idempotencyKey, String traceId, String studentId, String operatorToken) {
        String sourceHash = sha256(sourceCode);
        String fingerprint = sha256(problemId + "|" + language + "|" + sourceHash);

        // 幂等：同键同指纹返回首次结果；同键不同指纹 409
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            SubmissionEntity existing = submissionMapper.selectOne(new LambdaQueryWrapper<SubmissionEntity>()
                    .eq(SubmissionEntity::getStudentId, studentId)
                    .eq(SubmissionEntity::getIdempotencyKey, idempotencyKey)
                    .last("limit 1"));
            if (existing != null) {
                if (fingerprint.equals(existing.getRequestFingerprint())) {
                    return toView(existing);
                }
                throw new SubmissionException.Duplicate();
            }
        }

        validateSubmittableSession(contestId, operatorToken);
        Map<String, Object> problem = loadAllowedProblem(problemId, language, operatorToken);
        String problemVersionId = String.valueOf(problem.get("versionId"));
        Integer timeLimitMs = asInt(problem.get("timeLimitMs"));
        Integer memoryLimitKb = asInt(problem.get("memoryLimitKb"));

        // TODO: 校验四「题目属于竞赛」依赖竞赛题目分配表（exam-process §3 随机分配未落地）；
        // TODO: 校验五「提交次数未超限」阈值待确认（exam-process §5），确认前不做硬限制。

        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(UUID.randomUUID().toString());
        submission.setContestId(contestId);
        submission.setProblemId(problemId);
        submission.setProblemVersionId(problemVersionId);
        submission.setStudentId(studentId);
        submission.setLanguage(language);
        submission.setSourceCode(sourceCode);
        submission.setSourceHash(sourceHash);
        submission.setTimeLimitMs(timeLimitMs);
        submission.setMemoryLimitKb(memoryLimitKb);
        submission.setStatus("QUEUED");
        submission.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
        submission.setRequestFingerprint(fingerprint);
        submission.setCreatedAt(Instant.now());
        submission.setUpdatedAt(Instant.now());
        submissionMapper.insert(submission);

        // 同一事务写 Outbox（信封见 docs/api.md"事件信封"）
        writeOutbox("lunar-submission-created", submission.getId(), traceId, new LinkedHashMap<>(Map.of(
                "submissionId", submission.getId(),
                "contestId", contestId,
                "problemId", problemId,
                "problemVersionId", problemVersionId,
                "studentId", studentId,
                "language", language,
                "sourceCode", sourceCode,
                "timeLimitMs", timeLimitMs == null ? 0 : timeLimitMs,
                "memoryLimitKb", memoryLimitKb == null ? 0 : memoryLimitKb)));
        log.info("AUDIT event=SUBMISSION_CREATED submissionId={} contestId={} problemId={} studentId={}",
                submission.getId(), contestId, problemId, studentId);
        return toView(submission);
    }

    public SubmissionView get(String submissionId, String studentId, boolean operator) {
        SubmissionEntity submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw new SubmissionException.NotFound();
        }
        // 学生只能看自己的提交；老师/管理员可查任意（监考与阅卷）
        if (!operator && !submission.getStudentId().equals(studentId)) {
            throw new SubmissionException.NotFound();
        }
        return toView(submission);
    }

    /** 判决回写（判题链路内部接口；RocketMQ 接入后改为消费 lunar-submission-judged）。 */
    @Transactional
    public SubmissionView applyJudgment(String submissionId, String verdict, Integer score,
                                        Integer passedCases, Integer totalCases, String traceId) {
        SubmissionEntity submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw new SubmissionException.NotFound();
        }
        // 终态集合由 JudgeVerdict 定义（判题结果即终态），不再本地维护字符串清单
        if (JudgeVerdict.parse(submission.getStatus()).isPresent()) {
            throw new SubmissionException.InvalidStatusTransition(submission.getStatus());
        }
        JudgeVerdict judged = JudgeVerdict.parse(verdict)
                .orElseThrow(() -> new SubmissionException.InvalidStatusTransition("未知 verdict " + verdict));
        Instant updatedAt = Instant.now();
        // 条件更新（CAS）：仅当前态仍是我们读到的非终态时才写入——重复/乱序的判题结果不会覆盖终态
        LambdaUpdateWrapper<SubmissionEntity> update = new LambdaUpdateWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getId, submissionId)
                .eq(SubmissionEntity::getStatus, submission.getStatus())
                .set(SubmissionEntity::getStatus, judged.name())
                .set(SubmissionEntity::getScore, score)
                .set(SubmissionEntity::getPassedCases, passedCases)
                .set(SubmissionEntity::getTotalCases, totalCases)
                .set(SubmissionEntity::getUpdatedAt, updatedAt);
        if (submissionMapper.update(null, update) == 0) {
            SubmissionEntity current = submissionMapper.selectById(submissionId);
            throw new SubmissionException.InvalidStatusTransition(
                    current == null ? submission.getStatus() : current.getStatus());
        }
        submission.setStatus(judged.name());
        submission.setScore(score);
        submission.setPassedCases(passedCases);
        submission.setTotalCases(totalCases);
        submission.setUpdatedAt(updatedAt);

        writeOutbox("lunar-submission-judged", submissionId, traceId, new LinkedHashMap<>(Map.of(
                "submissionId", submissionId,
                "contestId", submission.getContestId(),
                "studentId", submission.getStudentId(),
                "verdict", judged.name(),
                "score", score == null ? 0 : score,
                "passedCases", passedCases == null ? 0 : passedCases,
                "totalCases", totalCases == null ? 0 : totalCases)));
        log.info("AUDIT event=SUBMISSION_JUDGED submissionId={} verdict={} score={}",
                submissionId, judged.name(), score);
        return toView(submission);
    }

    /** 校验一/二：会话有效 + 竞赛进行中（contest 服务间判定，转发学生令牌）。 */
    private void validateSubmittableSession(String contestId, String operatorToken) {
        Map<String, Object> sessionCheck;
        try {
            sessionCheck = upstream.checkSession(contestId, operatorToken);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new SubmissionException.ContestNotRunning("竞赛不存在");
        }
        if (sessionCheck == null) {
            throw new SubmissionException.ContestNotRunning("提交判定不可用");
        }
        if (!Boolean.TRUE.equals(sessionCheck.get("canSubmit"))) {
            String sessionStatus = String.valueOf(sessionCheck.get("sessionStatus"));
            String contestStatus = String.valueOf(sessionCheck.get("contestStatus"));
            if ("ACTIVE".equals(sessionStatus)) {
                throw new SubmissionException.ContestNotRunning("竞赛状态 " + contestStatus);
            }
            // 会话不存在/已过期/已终止：用注册表里的 SESSION_NOT_ACTIVE，而不是"不在名单"
            throw new SubmissionException.SessionNotActive(sessionStatus);
        }
    }

    /** 校验三：语言白名单；返回题目当前版本（含版本 ID 与判题限制，problem 服务间查询，考生视角无测试点）。 */
    private Map<String, Object> loadAllowedProblem(String problemId, String language, String operatorToken) {
        Map<String, Object> problem;
        try {
            problem = upstream.currentProblemVersion(problemId, operatorToken);
        } catch (HttpClientErrorException exception) {
            throw new SubmissionException.NotSubmittable("题目不可用");
        }
        @SuppressWarnings("unchecked")
        List<String> allowedLanguages = (List<String>) problem.get("allowedLanguages");
        if (allowedLanguages == null || !allowedLanguages.contains(language)) {
            throw new SubmissionException.LanguageNotAllowed();
        }
        return problem;
    }

    private void writeOutbox(String eventType, String aggregateId, String traceId, Map<String, Object> payload) {
        try {
            OutboxEventEntity event = new OutboxEventEntity();
            event.setId(UUID.randomUUID().toString());
            event.setEventId(UUID.randomUUID().toString());
            event.setEventType(eventType);
            event.setAggregateId(aggregateId);
            event.setSchemaVersion(1);
            event.setTraceId(traceId);
            event.setPayload(objectMapper.writeValueAsString(payload));
            event.setOccurredAt(Instant.now());
            event.setCreatedAt(Instant.now());
            outboxMapper.insert(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("事件序列化失败", exception);
        }
    }

    private static Integer asInt(Object rawValue) {
        return rawValue instanceof Number number ? number.intValue() : null;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static SubmissionView toView(SubmissionEntity submission) {
        return new SubmissionView(submission.getId(), submission.getContestId(), submission.getProblemId(),
                submission.getLanguage(), submission.getStatus(), submission.getScore(), submission.getPassedCases(),
                submission.getTotalCases(), submission.getCreatedAt());
    }
}
