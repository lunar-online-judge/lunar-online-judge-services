package io.lunar.judge.mq;

import io.lunar.common.event.EventEnvelope;
import io.lunar.common.event.EventMessages;
import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import io.lunar.common.judge.JudgeVerdict;
import io.lunar.judge.infra.ConsumedEventGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 消费 lunar-submission-created（docs/architecture.md §8 第 5-9 步）：
 * 调用 JudgeEngine（GO_JUDGE_BASE_URL 设置即真实引擎，否则 Mock）执行评测，
 * 归一化 verdict 并发布 lunar-submission-judged。
 *
 * 消息形态：同时兼容"带信封"（新）与"裸 payload"（旧）两种格式，见 {@link EventMessages}；
 * 上线消息体一律是完整信封，judged 侧的消费端才能按 eventId 幂等。
 */
@Component
@RocketMQMessageListener(topic = "lunar-submission-created", consumerGroup = "judge_created_group")
public class SubmissionCreatedConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(SubmissionCreatedConsumer.class);

    /** judged 事件的 Topic / eventType（本仓库两者同名，见 OutboxPublisher 的约定）。 */
    private static final String JUDGED_EVENT_TYPE = "lunar-submission-judged";

    /** 事件缺省限制：payload 未携带时的兜底值。 */
    private static final int DEFAULT_TIME_LIMIT_MS = 2000;
    private static final int DEFAULT_MEMORY_LIMIT_KB = 262144;

    /** 通过全部测试点记满分（部分通过按比例，见 scoreOf）。 */
    private static final int FULL_SCORE = 100;

    private final JudgeEngine judgeEngine;
    private final RocketMQTemplate rocketMQTemplate;
    private final ConsumedEventGuard consumedEventGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SubmissionCreatedConsumer(JudgeEngine judgeEngine, RocketMQTemplate rocketMQTemplate,
                                     ConsumedEventGuard consumedEventGuard) {
        this.judgeEngine = judgeEngine;
        this.rocketMQTemplate = rocketMQTemplate;
        this.consumedEventGuard = consumedEventGuard;
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode messageNode = objectMapper.readTree(message);
            JsonNode requestPayload = EventMessages.isEnvelope(messageNode)
                    ? messageNode.get(EventEnvelope.FIELD_PAYLOAD)
                    : messageNode;
            String eventId = EventMessages.textOf(messageNode, EventEnvelope.FIELD_EVENT_ID);
            if (consumedEventGuard.isProcessed(eventId)) {
                log.info("created 事件已处理过，跳过重复评测：eventId={}", eventId);
                return;
            }
            String submissionId = requestPayload.get("submissionId").asText();
            String traceId = resolveTraceId(messageNode, requestPayload);

            JudgeSubmissionResult submitted = judgeEngine.submit(toCommand(requestPayload));
            JudgeResult result = judgeEngine.query(submitted.engineSubmissionId());
            publishJudged(messageNode, requestPayload, submissionId, traceId, result);
            // 处理成功后才登记：中途失败时重投必须能重新评测（理由见 ConsumedEventGuard 的类注释）
            consumedEventGuard.markProcessed(eventId);
        } catch (Exception exception) {
            throw new RuntimeException("created 消息处理失败，触发重试", exception);
        }
    }

    /**
     * 发布 judged：消息体为完整信封，内层 payload 在原有字段之外**补齐提交的身份信息**。
     *
     * 为什么必须富化：judge 没有数据库，它只能把 created 事件里的身份字段原样带下去；
     * 而此前的 judged 只有 submissionId 与 verdict，除 submission 之外没有任何服务能独立消费它——
     * analytics 读模型要建"考试×学生×题目"的事实表，contestId/problemId/studentId 是唯一来源。
     */
    private void publishJudged(JsonNode messageNode, JsonNode requestPayload, String submissionId,
                               String traceId, JudgeResult result) throws JsonProcessingException {
        JudgeVerdict verdict = normalize(result.verdict());
        int passedCases = result.passedCases() == null ? 0 : result.passedCases();
        int totalCases = result.totalCases() == null ? 0 : result.totalCases();
        int score = scoreOf(verdict, result.passedCases(), result.totalCases());

        Map<String, Object> judgedPayload = new LinkedHashMap<>();
        judgedPayload.put("submissionId", submissionId);
        judgedPayload.put("contestId", EventMessages.textOf(requestPayload, "contestId"));
        judgedPayload.put("problemId", EventMessages.textOf(requestPayload, "problemId"));
        judgedPayload.put("problemVersionId", EventMessages.textOf(requestPayload, "problemVersionId"));
        judgedPayload.put("studentId", EventMessages.textOf(requestPayload, "studentId"));
        judgedPayload.put("language", EventMessages.textOf(requestPayload, "language"));
        judgedPayload.put("verdict", verdict.name());
        judgedPayload.put("score", score);
        judgedPayload.put("passedCases", passedCases);
        judgedPayload.put("totalCases", totalCases);
        judgedPayload.put("timeMs", result.timeMs() == null ? 0 : result.timeMs());
        judgedPayload.put("memoryKb", result.memoryKb() == null ? 0 : result.memoryKb());
        judgedPayload.put("message", result.message() == null ? "" : result.message());
        // 提交时间取 created 事件的 occurredAt（信封字段）：它是"服务端受理提交"的时刻，比 judged 时刻有意义
        judgedPayload.put("submittedAt", EventMessages.textOf(messageNode, EventEnvelope.FIELD_OCCURRED_AT));
        judgedPayload.put("traceId", traceId == null ? "" : traceId);

        String eventId = UUID.randomUUID().toString();
        String envelopeJson = EventEnvelope.ofNow(eventId, JUDGED_EVENT_TYPE, submissionId, traceId,
                        objectMapper.valueToTree(judgedPayload))
                .toJson(objectMapper);
        MessageBuilder<String> judgedMessage = MessageBuilder.withPayload(envelopeJson)
                .setHeader("KEYS", eventId);
        // traceId 为空时不能设该头：RocketMQ 拒绝空属性值，设了整条消息都发不出去
        if (traceId != null && !traceId.isBlank()) {
            judgedMessage.setHeader("traceId", traceId);
        }
        rocketMQTemplate.syncSend(JUDGED_EVENT_TYPE, judgedMessage.build());
        log.info("Judged published eventId={} submissionId={} verdict={} score={} passed={}/{} engineId={}",
                eventId, submissionId, verdict, score, passedCases, totalCases, submittedEngineId(result));
    }

    /** 引擎任务 ID 只在日志里有意义：单独取值以便日志语句保持在一行内。 */
    private static String submittedEngineId(JudgeResult result) {
        return result.engineSubmissionId() == null ? "" : result.engineSubmissionId();
    }

    /** traceId 优先取信封字段；旧格式（裸 payload）回落到 payload 内的 traceId。 */
    private static String resolveTraceId(JsonNode messageNode, JsonNode requestPayload) {
        String envelopeTraceId = EventMessages.textOf(messageNode, EventEnvelope.FIELD_TRACE_ID);
        return envelopeTraceId != null ? envelopeTraceId : EventMessages.textOf(requestPayload, "traceId");
    }

    /** created 事件的 payload → 引擎命令；限制值缺省时用兜底值（测试点缺失由引擎判为 SYSTEM_ERROR）。 */
    private static JudgeSubmissionCommand toCommand(JsonNode requestPayload) {
        String submissionId = requestPayload.get("submissionId").asText();
        String language = requestPayload.get("language").asText();
        // 源码按原样取（不用 textOf：它把空白视为"没有"，而源码是用户数据，不该被悄悄改写）
        String sourceCode = requestPayload.hasNonNull("sourceCode")
                ? requestPayload.get("sourceCode").asText() : "";
        int timeLimitMs = intOrDefault(requestPayload, "timeLimitMs", DEFAULT_TIME_LIMIT_MS);
        int memoryLimitKb = intOrDefault(requestPayload, "memoryLimitKb", DEFAULT_MEMORY_LIMIT_KB);
        return new JudgeSubmissionCommand(submissionId, language, sourceCode, parseTestFiles(requestPayload),
                timeLimitMs, memoryLimitKb);
    }

    private static int intOrDefault(JsonNode requestPayload, String fieldName, int defaultValue) {
        JsonNode fieldNode = requestPayload.get(fieldName);
        return fieldNode == null || fieldNode.isNull() ? defaultValue : fieldNode.asInt();
    }

    /** 事件 payload 里的测试点：{"case1.in": "...", "case1.out": "..."}；缺失/格式不对时返回空表。 */
    private static Map<String, String> parseTestFiles(JsonNode requestPayload) {
        JsonNode testFilesNode = requestPayload.get("testFiles");
        if (testFilesNode == null || !testFilesNode.isObject()) {
            return Map.of();
        }
        Map<String, String> testFiles = new LinkedHashMap<>();
        testFilesNode.fields().forEachRemaining(entry -> testFiles.put(entry.getKey(), entry.getValue().asText()));
        return testFiles;
    }

    /**
     * 评分（docs/exam-process.md §7）：通过全部测试点得满分，否则**按测试点通过比例**给部分分；
     * 引擎未上报测试点数时退回"ACCEPTED 满分、其余 0 分"。
     */
    private static int scoreOf(JudgeVerdict verdict, Integer passedCases, Integer totalCases) {
        if (passedCases == null || totalCases == null || totalCases <= 0) {
            return verdict == JudgeVerdict.ACCEPTED ? FULL_SCORE : 0;
        }
        return Math.round(passedCases * (float) FULL_SCORE / totalCases);
    }

    /** 引擎 verdict 归一化为判题终态：已知取值原样通过，未知（含尚未出结果的 PENDING）兜底 SYSTEM_ERROR。 */
    private static JudgeVerdict normalize(String verdict) {
        return JudgeVerdict.parse(verdict).orElse(JudgeVerdict.SYSTEM_ERROR);
    }
}
