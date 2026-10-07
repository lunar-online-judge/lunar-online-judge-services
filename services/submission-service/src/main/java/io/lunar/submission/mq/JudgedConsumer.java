package io.lunar.submission.mq;

import io.lunar.submission.domain.SubmissionException;
import io.lunar.submission.domain.SubmissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 消费 lunar-submission-judged：更新提交状态与成绩读模型（docs/architecture.md §8 第 10 步）。
 * 重复消息按终态保护幂等吞掉（IllegalTransition）；其余失败抛出触发 RocketMQ 重试。
 */
@Component
@RocketMQMessageListener(topic = "lunar-submission-judged", consumerGroup = "submission_judged_group")
public class JudgedConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(JudgedConsumer.class);

    private final SubmissionService submissionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JudgedConsumer(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode payload = objectMapper.readTree(message);
            String submissionId = payload.get("submissionId").asText();
            String verdict = payload.get("verdict").asText();
            Integer score = payload.hasNonNull("score") ? payload.get("score").asInt() : null;
            Integer passed = payload.hasNonNull("passedCases") ? payload.get("passedCases").asInt() : null;
            Integer total = payload.hasNonNull("totalCases") ? payload.get("totalCases").asInt() : null;
            String traceId = payload.hasNonNull("traceId") ? payload.get("traceId").asText() : null;
            submissionService.applyJudgment(submissionId, verdict, score, passed, total, traceId);
            log.info("Judged consumed submissionId={} verdict={}", submissionId, verdict);
        } catch (SubmissionException.InvalidStatusTransition e) {
            log.warn("重复判决消息，按幂等吞掉：{}", e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("judged 消息处理失败，触发重试", e);
        }
    }
}
