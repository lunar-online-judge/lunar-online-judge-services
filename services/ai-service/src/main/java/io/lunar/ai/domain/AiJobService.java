package io.lunar.ai.domain;

import io.lunar.ai.config.AiLimitsProperties;
import io.lunar.ai.config.AiProviderProperties;
import io.lunar.ai.config.AiWorkerProperties;
import io.lunar.ai.domain.dto.AiFindingView;
import io.lunar.ai.domain.dto.AiJobRequest;
import io.lunar.ai.domain.dto.AiJobView;
import io.lunar.ai.domain.dto.AiPage;
import io.lunar.ai.infra.AiFindingEntity;
import io.lunar.ai.infra.AiFindingMapper;
import io.lunar.ai.infra.AiJobEntity;
import io.lunar.ai.infra.AiJobMapper;
import io.lunar.ai.infra.AiQuotaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI 作业的完整生命周期（docs/services.md 的 ai-service 节）。
 *
 * 三件事都放在这里，因为它们必须共享同一个事务边界：
 *   ① 入队：配额占用 + 作业落库 + 审计留痕；
 *   ② 领取：`for update skip locked` + 租约；
 *   ③ 收尾：结论落库 + 派生成果（草稿 / 相似对）落库 + 成果事件 + 审计留痕 + 状态流转。
 *
 * **入队即扣配额**：配额若在"执行时"才检查，用户会先看到"已受理"再看到失败；
 * 而且入队是最便宜的拒绝点（此刻还没有任何 Provider 调用发生）。
 */
@Service
public class AiJobService {

    private static final Logger log = LoggerFactory.getLogger(AiJobService.class);

    /** 账本里的服务身份标识：AI 作业由后台 worker 执行，actor 是服务自己。 */
    private static final String SERVICE_ID = "ai-service";

    /** 与库列一致的长度上限（summary varchar(1000) / status_detail varchar(500)）。 */
    private static final int MAX_SUMMARY_LENGTH = 1000;
    private static final int MAX_DETAIL_LENGTH = 500;

    private final AiJobMapper jobMapper;
    private final AiFindingMapper findingMapper;
    private final AiQuotaMapper quotaMapper;
    private final ProblemDraftService problemDraftService;
    private final AiWorkerProperties workerProperties;
    private final AiLimitsProperties limitsProperties;
    private final AiProviderProperties providerProperties;

    public AiJobService(AiJobMapper jobMapper, AiFindingMapper findingMapper, AiQuotaMapper quotaMapper,
                        ProblemDraftService problemDraftService,
                        AiWorkerProperties workerProperties, AiLimitsProperties limitsProperties,
                        AiProviderProperties providerProperties) {
        this.jobMapper = jobMapper;
        this.findingMapper = findingMapper;
        this.quotaMapper = quotaMapper;
        this.problemDraftService = problemDraftService;
        this.workerProperties = workerProperties;
        this.limitsProperties = limitsProperties;
        this.providerProperties = providerProperties;
    }

    /**
     * 入队。返回已有作业表示**这份输入此前已经算过**（input_hash 命中），直接复用而不重复烧配额——
     * 老师连点两次"生成评审"是最常见的使用方式，不该产生两份作业与两次 Provider 调用。
     */
    @Transactional
    public AiJobView enqueue(AiJobRequest request, String traceId) {
        String promptVersion = PromptLibrary.promptVersionOf(request.jobType());
        String inputHash = inputHashOf(request, promptVersion);

        AiJobEntity reusable = findSucceededByInputHash(request.jobType(), inputHash);
        if (reusable != null) {
            log.info("相同输入的 AI 作业已完成，直接复用 jobId={} type={}", reusable.getId(), request.jobType());
            return AiJobView.of(reusable);
        }
        if (quotaMapper.tryConsume(request.requestedBy(), limitsProperties.getMaxJobsPerDay()) == 0) {
            throw new AiException.QuotaExceeded(limitsProperties.getMaxJobsPerDay());
        }

        AiJobEntity job = new AiJobEntity();
        job.setId(UUID.randomUUID().toString());
        job.setJobType(request.jobType().name());
        job.setStatus(AiJobStatus.PENDING.name());
        job.setContestId(request.contestId());
        job.setProblemId(request.problemId());
        job.setSubmissionId(request.submissionId());
        job.setStudentId(request.studentId());
        job.setRequestedBy(request.requestedBy());
        job.setRequestedRole(request.requestedRole());
        job.setInputHash(inputHash);
        job.setInputPayload(request.inputPayload());
        job.setTraceId(traceId);
        job.setPromptVersion(promptVersion);
        job.setProvider(providerProperties.getMode().name());
        job.setModel(providerProperties.getChatModel());
        job.setAttempts(0);
        job.setCreatedAt(Instant.now());
        jobMapper.insert(job);

        // 审计留痕走结构化日志（原为 Outbox → audit-service；按简化决定去掉这条链路，保留可追溯的日志）
        log.info("AUDIT event=AI_JOB_REQUESTED jobId={} type={} contestId={} by={} traceId={}",
                job.getId(), request.jobType(), request.contestId(), request.requestedBy(), traceId);
        return AiJobView.of(job);
    }

    /** 领取可执行作业（事务内 `for update skip locked` → 置 RUNNING 并加租约）。无作业可领时返回 null。 */
    @Transactional
    public AiJobEntity claimNext() {
        AiJobEntity job = jobMapper.lockNextRunnable();
        if (job == null) {
            return null;
        }
        Instant now = Instant.now();
        job.setStatus(AiJobStatus.RUNNING.name());
        job.setLeaseUntil(now.plusSeconds(workerProperties.getLeaseSeconds()));
        if (job.getStartedAt() == null) {
            job.setStartedAt(now);
        }
        jobMapper.updateById(job);
        return job;
    }

    /** 成功收尾：结论落库 + 成果事件 + 审计留痕 + 状态流转，全在同一事务里。 */
    @Transactional
    public AiFindingView markSucceeded(AiJobEntity job, AiFindingDraft draft, String traceId) {
        AiFindingEntity finding = new AiFindingEntity();
        finding.setId(UUID.randomUUID().toString());
        finding.setJobId(job.getId());
        // finding_type 直接取作业类型：两者本就一一对应，单独维护第二套枚举只会漂移
        finding.setFindingType(job.getJobType());
        finding.setContestId(job.getContestId());
        finding.setProblemId(job.getProblemId());
        finding.setSubmissionId(job.getSubmissionId());
        finding.setStudentId(job.getStudentId());
        finding.setSeverity(draft.severity());
        finding.setScore(draft.score());
        finding.setSummary(truncate(draft.summary(), MAX_SUMMARY_LENGTH));
        finding.setDetail(draft.detailJson());
        finding.setModel(job.getModel());
        finding.setPromptVersion(job.getPromptVersion());
        finding.setCreatedAt(Instant.now());
        findingMapper.insert(finding);

        // 用显式 set 而不是 updateById：MyBatis-Plus 默认不更新 null 字段，
        // 重试后成功的作业会残留上一次失败的 error_code/status_detail
        jobMapper.update(null, new LambdaUpdateWrapper<AiJobEntity>()
                .eq(AiJobEntity::getId, job.getId())
                .set(AiJobEntity::getStatus, AiJobStatus.SUCCEEDED.name())
                .set(AiJobEntity::getFinishedAt, Instant.now())
                .set(AiJobEntity::getLeaseUntil, null)
                .set(AiJobEntity::getStatusDetail, null)
                .set(AiJobEntity::getErrorCode, null));

        // 出题作业额外落一份草稿：必须在本事务内写，草稿才与 finding、事件同生共死；
        // 非出题作业在 writeFromCompletedJob 内直接返回。
        problemDraftService.writeFromCompletedJob(job, finding.getDetail());

        log.info("AUDIT event=AI_FINDING_CREATED findingId={} type={} contestId={} by={} traceId={}",
                finding.getId(), job.getJobType(), job.getContestId(), job.getRequestedBy(), traceId);
        return AiFindingView.of(finding);
    }

    /**
     * 失败收尾：累计尝试次数 + 指数退避；超过上限转 FAILED（终态）。
     *
     * 必须退避：不退避的话，一个必然失败的作业（例如 Provider 模型名写错）会在几秒内烧完所有尝试次数，
     * 运维看到的是"瞬时三次失败"，而不是"这个作业一直跑不通"。
     */
    @Transactional
    public void markFailed(AiJobEntity job, String errorCode, String detail, String traceId) {
        int attempts = (job.getAttempts() == null ? 0 : job.getAttempts()) + 1;
        boolean exhausted = attempts >= workerProperties.getMaxAttempts();
        LambdaUpdateWrapper<AiJobEntity> update = new LambdaUpdateWrapper<AiJobEntity>()
                .eq(AiJobEntity::getId, job.getId())
                .set(AiJobEntity::getAttempts, attempts)
                .set(AiJobEntity::getErrorCode, errorCode)
                .set(AiJobEntity::getStatusDetail, truncate(detail, MAX_DETAIL_LENGTH))
                .set(AiJobEntity::getLeaseUntil, null);
        if (exhausted) {
            update.set(AiJobEntity::getStatus, AiJobStatus.FAILED.name())
                    .set(AiJobEntity::getFinishedAt, Instant.now());
        } else {
            update.set(AiJobEntity::getStatus, AiJobStatus.PENDING.name())
                    .set(AiJobEntity::getNextAttemptAt, Instant.now().plusSeconds(backoffSeconds(attempts)));
        }
        jobMapper.update(null, update);
        log.warn("AUDIT event=AI_JOB_FAILED jobId={} errorCode={} attempts={} exhausted={} traceId={}",
                job.getId(), errorCode, attempts, exhausted, traceId);
        log.warn("AI 作业失败 jobId={} errorCode={} attempts={} exhausted={}",
                job.getId(), errorCode, attempts, exhausted);
    }

    public AiJobView get(String jobId) {
        AiJobEntity job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new AiException.JobNotFound();
        }
        return AiJobView.of(job);
    }

    public AiPage<AiJobView> listJobs(String contestId, String jobType, String status, long page, long pageSize) {
        LambdaQueryWrapper<AiJobEntity> query = new LambdaQueryWrapper<AiJobEntity>()
                .orderByDesc(AiJobEntity::getCreatedAt);
        if (contestId != null && !contestId.isBlank()) {
            query.eq(AiJobEntity::getContestId, contestId);
        }
        if (jobType != null && !jobType.isBlank()) {
            query.eq(AiJobEntity::getJobType, jobType);
        }
        if (status != null && !status.isBlank()) {
            query.eq(AiJobEntity::getStatus, status);
        }
        Page<AiJobEntity> result = jobMapper.selectPage(new Page<>(page, pageSize), query);
        return new AiPage<>(result.getRecords().stream().map(AiJobView::of).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public AiPage<AiFindingView> listFindings(String contestId, String submissionId, String findingType,
                                              long page, long pageSize) {
        LambdaQueryWrapper<AiFindingEntity> query = new LambdaQueryWrapper<AiFindingEntity>()
                .orderByDesc(AiFindingEntity::getCreatedAt);
        if (contestId != null && !contestId.isBlank()) {
            query.eq(AiFindingEntity::getContestId, contestId);
        }
        if (submissionId != null && !submissionId.isBlank()) {
            query.eq(AiFindingEntity::getSubmissionId, submissionId);
        }
        if (findingType != null && !findingType.isBlank()) {
            query.eq(AiFindingEntity::getFindingType, findingType);
        }
        Page<AiFindingEntity> result = findingMapper.selectPage(new Page<>(page, pageSize), query);
        return new AiPage<>(result.getRecords().stream().map(AiFindingView::of).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public AiFindingView getFinding(String findingId) {
        AiFindingEntity finding = findingMapper.selectById(findingId);
        if (finding == null) {
            throw new AiException.FindingNotFound();
        }
        return AiFindingView.of(finding);
    }

    /** 成果事件的载荷：只带引用与结论摘要，**不带源码与题面**（事件会被多方消费，不该顺带扩散学生代码）。 */
    private static Map<String, Object> findingEventPayload(AiFindingEntity finding) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findingId", finding.getId());
        payload.put("jobId", finding.getJobId());
        payload.put("findingType", finding.getFindingType());
        payload.put("contestId", finding.getContestId());
        payload.put("problemId", finding.getProblemId());
        payload.put("submissionId", finding.getSubmissionId());
        payload.put("studentId", finding.getStudentId());
        payload.put("severity", finding.getSeverity());
        payload.put("score", finding.getScore());
        payload.put("summary", finding.getSummary());
        payload.put("model", finding.getModel());
        payload.put("promptVersion", finding.getPromptVersion());
        payload.put("createdAt", finding.getCreatedAt().toString());
        return payload;
    }

    /**
     * 输入指纹：作业类型 + 全部引用 + 类型专属输入 + 提示词版本。
     *
     * 含提示词版本是刻意的：换了提示词就该重算，否则"升级提示词后老结论还在被复用"，
     * 会让人误以为新提示词没效果。
     */
    private static String inputHashOf(AiJobRequest request, String promptVersion) {
        String material = String.join("|",
                request.jobType().name(),
                nullSafe(request.contestId()),
                nullSafe(request.problemId()),
                nullSafe(request.submissionId()),
                nullSafe(request.studentId()),
                nullSafe(request.inputPayload()),
                promptVersion);
        return sha256(material);
    }

    private AiJobEntity findSucceededByInputHash(AiJobType jobType, String inputHash) {
        return jobMapper.selectOne(new LambdaQueryWrapper<AiJobEntity>()
                .eq(AiJobEntity::getJobType, jobType.name())
                .eq(AiJobEntity::getInputHash, inputHash)
                .eq(AiJobEntity::getStatus, AiJobStatus.SUCCEEDED.name())
                .orderByDesc(AiJobEntity::getFinishedAt)
                .last("limit 1"));
    }

    private static long backoffSeconds(int attempts) {
        return Math.min(300L, 1L << Math.min(attempts, 8));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
