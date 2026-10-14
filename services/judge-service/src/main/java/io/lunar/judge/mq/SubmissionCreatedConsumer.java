package io.lunar.judge.mq;

import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 消费 lunar-submission-created（docs/architecture.md §8 第 5-9 步）：
 * 调用 JudgeEngine（GO_JUDGE_BASE_URL 设置即真实引擎，否则 Mock）执行评测，
 * 归一化 verdict 并发布 lunar-submission-judged。
 * v1：测试点经事件传输待服务间接口落地（Mock 引擎不依赖测试点；真实引擎缺测试点将返回 SYSTEM_ERROR）。
 */
@Component
@RocketMQMessageListener(topic = "lunar-submission-created", consumerGroup = "judge_created_group")
public class SubmissionCreatedConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(SubmissionCreatedConsumer.class);

    /** 标准 verdict 码（对下游 submission-service 的契约值，禁改）。 */
    private static final String VERDICT_ACCEPTED = "ACCEPTED";
    private static final String VERDICT_WRONG_ANSWER = "WRONG_ANSWER";
    private static final String VERDICT_COMPILE_ERROR = "COMPILE_ERROR";
    private static final String VERDICT_TIME_LIMIT_EXCEEDED = "TIME_LIMIT_EXCEEDED";
    private static final String VERDICT_MEMORY_LIMIT_EXCEEDED = "MEMORY_LIMIT_EXCEEDED";
    private static final String VERDICT_RUNTIME_ERROR = "RUNTIME_ERROR";
    private static final String VERDICT_OUTPUT_LIMIT_EXCEEDED = "OUTPUT_LIMIT_EXCEEDED";
    private static final String VERDICT_SYSTEM_ERROR = "SYSTEM_ERROR";

    /** 事件缺省限制：payload 未携带时的兜底值。 */
    private static final int DEFAULT_TIME_LIMIT_MS = 2000;
    private static final int DEFAULT_MEMORY_LIMIT_KB = 262144;

    /** ACCEPTED 记满分（其余记 0 分）。 */
    private static final int ACCEPTED_SCORE = 100;

    private final JudgeEngine judgeEngine;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SubmissionCreatedConsumer(JudgeEngine judgeEngine, RocketMQTemplate rocketMQTemplate) {
        this.judgeEngine = judgeEngine;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode payload = objectMapper.readTree(message);
            String submissionId = payload.get("submissionId").asText();
            String language = payload.get("language").asText();
            String sourceCode = payload.hasNonNull("sourceCode") ? payload.get("sourceCode").asText() : "";
            int timeLimitMs = payload.hasNonNull("timeLimitMs")
                    ? payload.get("timeLimitMs").asInt() : DEFAULT_TIME_LIMIT_MS;
            int memoryLimitKb = payload.hasNonNull("memoryLimitKb")
                    ? payload.get("memoryLimitKb").asInt() : DEFAULT_MEMORY_LIMIT_KB;
            String traceId = payload.hasNonNull("traceId") ? payload.get("traceId").asText() : null;

            JudgeSubmissionCommand command = new JudgeSubmissionCommand(
                    submissionId, language, sourceCode, Map.of(), timeLimitMs, memoryLimitKb);
            JudgeSubmissionResult submitted = judgeEngine.submit(command);
            JudgeResult result = judgeEngine.query(submitted.engineSubmissionId());

            String verdict = normalize(result.verdict());
            int score = VERDICT_ACCEPTED.equals(verdict) ? ACCEPTED_SCORE : 0;
            String judged = objectMapper.writeValueAsString(Map.of(
                    "submissionId", submissionId,
                    "verdict", verdict,
                    "score", score,
                    "timeMs", result.timeMs() == null ? 0 : result.timeMs(),
                    "memoryKb", result.memoryKb() == null ? 0 : result.memoryKb(),
                    "message", result.message() == null ? "" : result.message(),
                    "traceId", traceId == null ? "" : traceId));
            rocketMQTemplate.syncSend("lunar-submission-judged", judged);
            log.info("Judged published submissionId={} verdict={} engineId={}",
                    submissionId, verdict, submitted.engineSubmissionId());
        } catch (Exception exception) {
            throw new RuntimeException("created 消息处理失败，触发重试", exception);
        }
    }

    /** 引擎 verdict（QUEUED/RUNNING 等）归一化为标准结果，未知一律 SYSTEM_ERROR。 */
    private static String normalize(String verdict) {
        return switch (verdict == null ? "" : verdict) {
            case VERDICT_ACCEPTED, VERDICT_WRONG_ANSWER, VERDICT_COMPILE_ERROR, VERDICT_TIME_LIMIT_EXCEEDED,
                 VERDICT_MEMORY_LIMIT_EXCEEDED, VERDICT_RUNTIME_ERROR, VERDICT_OUTPUT_LIMIT_EXCEEDED -> verdict;
            default -> VERDICT_SYSTEM_ERROR;
        };
    }
}
