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

import java.util.LinkedHashMap;
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

    /** 通过全部测试点记满分（部分通过按比例，见 scoreOf）。 */
    private static final int FULL_SCORE = 100;

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
            // 测试点随事件传输（submission 已按 <name>.in / <name>.out 组装）；缺失时引擎返回 SYSTEM_ERROR
            Map<String, String> testFiles = parseTestFiles(payload);

            JudgeSubmissionCommand command = new JudgeSubmissionCommand(
                    submissionId, language, sourceCode, testFiles, timeLimitMs, memoryLimitKb);
            JudgeSubmissionResult submitted = judgeEngine.submit(command);
            JudgeResult result = judgeEngine.query(submitted.engineSubmissionId());

            JudgeVerdict verdict = normalize(result.verdict());
            int passedCases = result.passedCases() == null ? 0 : result.passedCases();
            int totalCases = result.totalCases() == null ? 0 : result.totalCases();
            int score = scoreOf(verdict, result.passedCases(), result.totalCases());
            String judged = objectMapper.writeValueAsString(Map.of(
                    "submissionId", submissionId,
                    "verdict", verdict.name(),
                    "score", score,
                    "passedCases", passedCases,
                    "totalCases", totalCases,
                    "timeMs", result.timeMs() == null ? 0 : result.timeMs(),
                    "memoryKb", result.memoryKb() == null ? 0 : result.memoryKb(),
                    "message", result.message() == null ? "" : result.message(),
                    "traceId", traceId == null ? "" : traceId));
            rocketMQTemplate.syncSend("lunar-submission-judged", judged);
            log.info("Judged published submissionId={} verdict={} score={} passed={}/{} engineId={}",
                    submissionId, verdict, score, passedCases, totalCases, submitted.engineSubmissionId());
        } catch (Exception exception) {
            throw new RuntimeException("created 消息处理失败，触发重试", exception);
        }
    }

    /** 事件 payload 里的测试点：{"case1.in": "...", "case1.out": "..."}；缺失/格式不对时返回空表。 */
    private static Map<String, String> parseTestFiles(JsonNode payload) {
        JsonNode testFilesNode = payload.get("testFiles");
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
