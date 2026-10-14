package io.lunar.judge.mq;

import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import io.lunar.common.judge.JudgeVerdict;
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

            JudgeVerdict verdict = normalize(result.verdict());
            int score = verdict == JudgeVerdict.ACCEPTED ? ACCEPTED_SCORE : 0;
            String judged = objectMapper.writeValueAsString(Map.of(
                    "submissionId", submissionId,
                    "verdict", verdict.name(),
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

    /** 引擎 verdict 归一化为判题终态：已知取值原样通过，未知（含尚未出结果的 PENDING）兜底 SYSTEM_ERROR。 */
    private static JudgeVerdict normalize(String verdict) {
        return JudgeVerdict.parse(verdict).orElse(JudgeVerdict.SYSTEM_ERROR);
    }
}
