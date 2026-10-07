package io.lunar.submission.domain;

import io.lunar.submission.infra.OutboxEventEntity;
import io.lunar.submission.infra.OutboxEventMapper;
import io.lunar.submission.infra.SubmissionEntity;
import io.lunar.submission.infra.SubmissionMapper;
import io.lunar.submission.infra.UpstreamClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * 事务：submission(QUEUED) 与 Outbox(submission.created) 同一本地事务落库；Outbox 投递待 RocketMQ 接入。
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
    private static final List<String> TERMINAL_STATUSES = List.of(
            "ACCEPTED", "WRONG_ANSWER", "COMPILE_ERROR", "TIME_LIMIT_EXCEEDED",
            "MEMORY_LIMIT_EXCEEDED", "RUNTIME_ERROR", "OUTPUT_LIMIT_EXCEEDED", "SYSTEM_ERROR");

    public record SubmissionView(String submissionId, String contestId, String problemId, String language,
                                 String status, Integer score, Integer passedCases, Integer totalCases,
                                 Instant createdAt) {}

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

        // 校验一/二：会话有效 + 竞赛进行中（contest 服务间判定，转发学生令牌）
        Map<String, Object> check;
        try {
            check = upstream.checkSession(contestId, operatorToken);
        } catch (HttpClientErrorException.NotFound e) {
            throw new SubmissionException.ContestNotRunning("竞赛不存在");
        }
        if (check == null) {
            throw new SubmissionException.ContestNotRunning("提交判定不可用");
        }
        if (!Boolean.TRUE.equals(check.get("canSubmit"))) {
            String sessionStatus = String.valueOf(check.get("sessionStatus"));
            String contestStatus = String.valueOf(check.get("contestStatus"));
            if ("ACTIVE".equals(sessionStatus)) {
                throw new SubmissionException.ContestNotRunning("竞赛状态 " + contestStatus);
            }
            throw new SubmissionException.NotSubmittable("会话状态 " + sessionStatus);
        }

        // 校验三：语言白名单 + 取题目版本与判题限制（problem 服务间查询，考生视角无测试点）
        Map<String, Object> problem;
        try {
            problem = upstream.currentProblemVersion(problemId, operatorToken);
        } catch (HttpClientErrorException e) {
            throw new SubmissionException.NotSubmittable("题目不可用");
        }
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) problem.get("allowedLanguages");
        if (allowed == null || !allowed.contains(language)) {
            throw new SubmissionException.LanguageNotAllowed();
        }
        String problemVersionId = String.valueOf(problem.get("versionId"));
        Integer timeLimitMs = asInt(problem.get("timeLimitMs"));
        Integer memoryLimitKb = asInt(problem.get("memoryLimitKb"));

        // TODO: 校验四「题目属于竞赛」依赖竞赛题目分配表（exam-process §3 随机分配未落地）；
        // TODO: 校验五「提交次数未超限」阈值待确认（exam-process §5），确认前不做硬限制。

        SubmissionEntity entity = new SubmissionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setContestId(contestId);
        entity.setProblemId(problemId);
        entity.setProblemVersionId(problemVersionId);
        entity.setStudentId(studentId);
        entity.setLanguage(language);
        entity.setSourceCode(sourceCode);
        entity.setSourceHash(sourceHash);
        entity.setTimeLimitMs(timeLimitMs);
        entity.setMemoryLimitKb(memoryLimitKb);
        entity.setStatus("QUEUED");
        entity.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
        entity.setRequestFingerprint(fingerprint);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        submissionMapper.insert(entity);

        // 同一事务写 Outbox（信封见 docs/api.md"事件信封"）
        writeOutbox("lunar-submission-created", entity.getId(), traceId, new LinkedHashMap<>(Map.of(
                "submissionId", entity.getId(),
                "contestId", contestId,
                "problemId", problemId,
                "problemVersionId", problemVersionId,
                "studentId", studentId,
                "language", language,
                "sourceCode", sourceCode,
                "timeLimitMs", timeLimitMs == null ? 0 : timeLimitMs,
                "memoryLimitKb", memoryLimitKb == null ? 0 : memoryLimitKb)));
        log.info("AUDIT event=SUBMISSION_CREATED submissionId={} contestId={} problemId={} studentId={}",
                entity.getId(), contestId, problemId, studentId);
        return toView(entity);
    }

    public SubmissionView get(String submissionId, String studentId, boolean operator) {
        SubmissionEntity entity = submissionMapper.selectById(submissionId);
        if (entity == null) {
            throw new SubmissionException.NotFound();
        }
        // 学生只能看自己的提交；老师/管理员可查任意（监考与阅卷）
        if (!operator && !entity.getStudentId().equals(studentId)) {
            throw new SubmissionException.NotFound();
        }
        return toView(entity);
    }

    /** 判决回写（判题链路内部接口；RocketMQ 接入后改为消费 lunar-submission-judged）。 */
    @Transactional
    public SubmissionView applyJudgment(String submissionId, String verdict, Integer score,
                                        Integer passedCases, Integer totalCases, String traceId) {
        SubmissionEntity entity = submissionMapper.selectById(submissionId);
        if (entity == null) {
            throw new SubmissionException.NotFound();
        }
        if (TERMINAL_STATUSES.contains(entity.getStatus())) {
            throw new SubmissionException.InvalidStatusTransition(entity.getStatus());
        }
        if (!TERMINAL_STATUSES.contains(verdict)) {
            throw new SubmissionException.InvalidStatusTransition("未知 verdict " + verdict);
        }
        entity.setStatus(verdict);
        entity.setScore(score);
        entity.setPassedCases(passedCases);
        entity.setTotalCases(totalCases);
        entity.setUpdatedAt(Instant.now());
        submissionMapper.updateById(entity);

        writeOutbox("lunar-submission-judged", submissionId, traceId, new LinkedHashMap<>(Map.of(
                "submissionId", submissionId,
                "contestId", entity.getContestId(),
                "studentId", entity.getStudentId(),
                "verdict", verdict,
                "score", score == null ? 0 : score,
                "passedCases", passedCases == null ? 0 : passedCases,
                "totalCases", totalCases == null ? 0 : totalCases)));
        log.info("AUDIT event=SUBMISSION_JUDGED submissionId={} verdict={} score={}",
                submissionId, verdict, score);
        return toView(entity);
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
    }

    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static SubmissionView toView(SubmissionEntity e) {
        return new SubmissionView(e.getId(), e.getContestId(), e.getProblemId(), e.getLanguage(),
                e.getStatus(), e.getScore(), e.getPassedCases(), e.getTotalCases(), e.getCreatedAt());
    }
}
