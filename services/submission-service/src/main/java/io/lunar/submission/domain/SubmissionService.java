package io.lunar.submission.domain;

import io.lunar.common.judge.JudgeVerdict;
import io.lunar.submission.config.SubmissionLimitProperties;
import io.lunar.submission.domain.dto.InternalSubmissionDetail;
import io.lunar.submission.domain.dto.InternalSubmissionSummary;
import io.lunar.submission.domain.dto.SubmissionView;
import io.lunar.submission.infra.ConsumedEventMapper;
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
import org.springframework.beans.factory.annotation.Value;
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
 * 五项校验中已实现四项——会话有效、竞赛进行中（contest 服务间判定）、语言允许（problem 白名单）、
 * 提交频率未超限（按学生做窗口计数，阈值经 Nacos 热更新）；「题目属于竞赛」依赖随机分配关联表
 * （docs/exam-process.md §3 未落地），代码中标注而不假装校验。
 * 幂等：Idempotency-Key 同学生唯一；同键同指纹返回首次结果（200），同键不同指纹 409 SUBMISSION_DUPLICATE。
 * 事务：submission(QUEUED) 与 Outbox(submission.created) 同一本地事务落库，由 OutboxPublisher 投递。
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    /** 判决事件的消费组名：幂等闸门按它区分"同一 eventId 是否已被本消费者处理过"。 */
    private static final String JUDGED_CONSUMER = "submission_judged_group";

    /**
     * 服务间批量读取的硬上限。批量源码接口是最大的数据外泄面，上限必须由服务端写死——
     * 调用方传再大也只拿到这么多。
     */
    private static final int MAX_INTERNAL_LIST_SIZE = 500;

    private final SubmissionMapper submissionMapper;
    private final OutboxEventMapper outboxMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final UpstreamClient upstream;
    private final SubmissionLimitProperties limitProperties;
    private final String serviceName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SubmissionService(SubmissionMapper submissionMapper, OutboxEventMapper outboxMapper,
                             ConsumedEventMapper consumedEventMapper, UpstreamClient upstream,
                             SubmissionLimitProperties limitProperties,
                             @Value("${spring.application.name}") String serviceName) {
        this.submissionMapper = submissionMapper;
        this.outboxMapper = outboxMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.upstream = upstream;
        this.limitProperties = limitProperties;
        this.serviceName = serviceName;
    }

    @Transactional
    public SubmissionView submit(String contestId, String problemId, String language, String sourceCode,
                                 String idempotencyKey, String traceId, String studentId, String operatorToken) {
        String sourceHash = sha256(sourceCode);
        // 指纹必须含 contestId（BE-04）：否则同一个幂等键可以跨考试复用，第二次会被判成"同键异体"或误返首次结果
        String fingerprint = sha256(contestId + "|" + problemId + "|" + language + "|" + sourceHash);

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
        // 校验四「题目属于竞赛」+ 版本快照（BE-04）：版本以**考试锁定值**为准，而不是题目当前发布版本，
        // 否则考试中途的全局发布会改掉题面与测试数据（problemVersionId 随事件送到判题引擎）
        String problemVersionId;
        try {
            problemVersionId = upstream.lockedProblemVersion(contestId, problemId, operatorToken);
        } catch (HttpClientErrorException.NotFound exception) {
            // 上游 404 = 该题目不在考试锁定清单里；映射成稳定错误码而不是 500
            throw new SubmissionException.ProblemNotInContest();
        }
        if (problemVersionId == null) {
            throw new SubmissionException.ProblemNotInContest();
        }
        Map<String, Object> lockedVersion = upstream.problemVersion(problemId, problemVersionId);
        Integer timeLimitMs = asInt(lockedVersion.get("timeLimitMs"));
        Integer memoryLimitKb = asInt(lockedVersion.get("memoryLimitKb"));
        // 判题链路取测试点（problem 服务间端点）：随 created 事件送到评测引擎；
        // 不取的话真实引擎没有测试数据，只能返回 SYSTEM_ERROR（docs/architecture.md §8 第 5 步）
        Map<String, String> testFiles = toTestFiles(
                upstream.problemTestcases(problemId, problemVersionId));

        // 校验四「题目属于竞赛」仍依赖竞赛题目分配表（exam-process §3 随机分配未落地）。
        enforceRateLimit(studentId);

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
                "memoryLimitKb", memoryLimitKb == null ? 0 : memoryLimitKb,
                "testFiles", testFiles)));
        log.info("AUDIT event=SUBMISSION_CREATED submissionId={} contestId={} problemId={} studentId={}",
                submission.getId(), contestId, problemId, studentId);
        // 审计留痕改走本地 Outbox + 异步投递（BE-07）：与提交落库同事务
        writeAuditOutbox(submission.getId(), contestId, "SUBMISSION_CREATED", studentId, "CANDIDATE",
                null, "题目 " + problemId + " 语言 " + language, traceId);
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

    /**
     * 服务间详情读取（**含源码**，只给服务身份调用方）。
     *
     * 与 {@link #get} 的区别是"不做学生/教师判定"：调用方是服务（当前是 ai-service 的代码评审作业），
     * 授权由端点上的 {@code hasRole('SERVICE')} 完成，这里只负责取数据。
     */
    public InternalSubmissionDetail internalDetail(String submissionId) {
        SubmissionEntity submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw new SubmissionException.NotFound();
        }
        return new InternalSubmissionDetail(submission.getId(), submission.getContestId(), submission.getProblemId(),
                submission.getProblemVersionId(), submission.getStudentId(), submission.getLanguage(),
                submission.getStatus(), submission.getScore(), submission.getPassedCases(),
                submission.getTotalCases(), submission.getSourceCode());
    }

    /**
     * 服务间批量读取（**含源码**，只给服务身份调用方）：供 ai-service 的语义查重取语料。
     *
     * {@code limit} 由服务端夹取到 {@value #MAX_INTERNAL_LIST_SIZE} 以内：批量接口是最大的数据外泄面，
     * 上限不能由调用方决定。按提交时间升序，保证同一批数据两次拉取顺序一致（相似度结果才可比）。
     */
    public List<InternalSubmissionSummary> internalList(String contestId, String problemId, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_INTERNAL_LIST_SIZE));
        return submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                        .eq(SubmissionEntity::getContestId, contestId)
                        .eq(SubmissionEntity::getProblemId, problemId)
                        .orderByAsc(SubmissionEntity::getCreatedAt)
                        .last("limit " + effectiveLimit))
                .stream()
                .map(SubmissionService::toSummary)
                .toList();
    }

    /**
     * 判决回写（判题链路；MQ 消费是正常路径，内部端点保留给补偿重放）。
     *
     * {@code eventId} 是信封事件 ID，可为空（旧格式消息与内部重放端点）：非空时先过幂等闸门，
     * 重复投递直接返回当前状态——既不再改库，也不再补发 judged 事件。
     */
    @Transactional
    public SubmissionView applyJudgment(String submissionId, String eventId, String verdict, Integer score,
                                        Integer passedCases, Integer totalCases, String traceId) {
        if (isAlreadyProcessed(eventId)) {
            SubmissionEntity processed = submissionMapper.selectById(submissionId);
            if (processed == null) {
                throw new SubmissionException.NotFound();
            }
            log.info("judged 事件重复投递，跳过：eventId={} submissionId={}", eventId, submissionId);
            return toView(processed);
        }
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

        // judged 有两个生产者（judge 直发、本服务的内部回写端点），内层 payload 形状必须一致——
        // analytics 读模型按同一套字段消费，形状不一致会让其中一路的 contestId/problemId 为空。
        Map<String, Object> judgedPayload = new LinkedHashMap<>();
        judgedPayload.put("submissionId", submissionId);
        judgedPayload.put("contestId", submission.getContestId());
        judgedPayload.put("problemId", submission.getProblemId());
        judgedPayload.put("problemVersionId", submission.getProblemVersionId());
        judgedPayload.put("studentId", submission.getStudentId());
        judgedPayload.put("language", submission.getLanguage());
        judgedPayload.put("verdict", judged.name());
        judgedPayload.put("score", score == null ? 0 : score);
        judgedPayload.put("passedCases", passedCases == null ? 0 : passedCases);
        judgedPayload.put("totalCases", totalCases == null ? 0 : totalCases);
        // 提交时间 = 受理提交的时刻，与 judge 直发路径语义一致（那边取 created 事件的 occurredAt）
        judgedPayload.put("submittedAt",
                submission.getCreatedAt() == null ? null : submission.getCreatedAt().toString());
        judgedPayload.put("traceId", traceId == null ? "" : traceId);
        writeOutbox("lunar-submission-judged", submissionId, traceId, judgedPayload);
        // 判决回写由 MQ 消费者触发（没有用户令牌）→ 走服务身份（账本 actor=submission-service、role=SERVICE）
        log.info("AUDIT event=SUBMISSION_JUDGED submissionId={} verdict={} score={}",
                submissionId, judged.name(), score);
        // 判决回写没有用户上下文 → actor 记服务身份（BE-07：actor 是事件快照，不重放用户 Bearer）
        writeAuditOutbox(submissionId, submission.getContestId(), "SUBMISSION_JUDGED", null, "SERVICE",
                serviceName, "verdict=" + judged.name() + " score=" + score, traceId);
        return toView(submission);
    }

    /**
     * 幂等闸门（docs/api.md"事件信封"）：把 eventId 登记进 consumed_events，返回是否"已经处理过"。
     *
     * 与调用方在同一个事务里执行（调用方是 {@code @Transactional}），所以事务回滚时登记一起回滚——
     * 这正是"登记"必须与"事实写入"同事务的原因：否则会出现登记成功、判决未落库、消息已 ack 的丢判决路径。
     *
     * eventId 为空（旧格式裸 payload 消息、内部重放端点）时不做闸门，退化为原有的终态保护（CAS）幂等。
     */
    private boolean isAlreadyProcessed(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        return consumedEventMapper.tryMarkProcessed(eventId, JUDGED_CONSUMER) == 0;
    }

    /**
     * 校验五（docs/exam-process.md §7）：窗口内提交频率限制与反滥用。
     * 计数以 submissions 表为权威（本服务不依赖 Redis），窗口与阈值可经 Nacos 热更新；
     * 并发提交可能同时通过计数——这是软限制，可接受，不为此引入分布式锁。
     */
    private void enforceRateLimit(String studentId) {
        Instant windowStart = Instant.now().minusSeconds(limitProperties.getWindowSeconds());
        Long recentSubmissions = submissionMapper.selectCount(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getStudentId, studentId)
                .ge(SubmissionEntity::getCreatedAt, windowStart));
        if (recentSubmissions != null && recentSubmissions >= limitProperties.getMaxPerWindow()) {
            throw new SubmissionException.LimitExceeded(
                    limitProperties.getMaxPerWindow(), limitProperties.getWindowSeconds());
        }
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

    /**
     * 审计留痕事件（lunar-audit-recorded，BACKEND_IMPROVEMENT_PLAN BE-07）：与业务同事务写本地 Outbox，
     * 由 OutboxPublisher 异步投递、audit-service 消费端按 eventId 幂等；actor 作为事件快照保存，
     * 不再同步调 audit-service、也不再转发用户 Bearer（token 到期不会让补偿永远失败）。
     */
    private void writeAuditOutbox(String aggregateId, String contestId, String action, String actorId,
                                  String actorRole, String serviceId, String detail, String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("actorId", actorId);
        payload.put("actorRole", actorRole);
        payload.put("serviceId", serviceId);
        payload.put("targetType", "SUBMISSION");
        payload.put("targetId", aggregateId);
        payload.put("contestId", contestId);
        payload.put("detail", detail);
        payload.put("traceId", traceId);
        writeOutbox("lunar-audit-recorded", aggregateId, traceId, payload);
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

    /**
     * 测试点 → JudgeEngine 约定的 testFiles：{@code <name>.in} / {@code <name>.out}
     * （见 judge-service 的 GoJudgeAdapter.parseTestCases；缺期望输出的用例会被判为 SYSTEM_ERROR）。
     */
    private static Map<String, String> toTestFiles(List<Map<String, Object>> testcases) {
        Map<String, String> testFiles = new LinkedHashMap<>();
        for (Map<String, Object> testcase : testcases) {
            String name = String.valueOf(testcase.get("name"));
            testFiles.put(name + ".in", textOf(testcase.get("inputContent")));
            testFiles.put(name + ".out", textOf(testcase.get("expectedOutput")));
        }
        return testFiles;
    }

    private static String textOf(Object rawValue) {
        return rawValue == null ? "" : String.valueOf(rawValue);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static InternalSubmissionSummary toSummary(SubmissionEntity submission) {
        return new InternalSubmissionSummary(submission.getId(), submission.getStudentId(),
                submission.getLanguage(), submission.getStatus(), submission.getSourceHash(),
                submission.getSourceCode());
    }

    private static SubmissionView toView(SubmissionEntity submission) {        return new SubmissionView(submission.getId(), submission.getContestId(), submission.getProblemId(),
                submission.getLanguage(), submission.getStatus(), submission.getScore(), submission.getPassedCases(),
                submission.getTotalCases(), submission.getCreatedAt());
    }
}
