package io.lunar.submission.mq;

import io.lunar.common.event.EventEnvelope;
import io.lunar.common.event.EventMessages;
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
 *
 * 幂等分两层：① 信封里的 eventId 走 consumed_events 闸门（同一事件重复投递只处理一次）；
 * ② 没有 eventId 的旧格式消息退化为终态条件更新（CAS）。
 * 其余失败抛出异常触发 RocketMQ 重试。
 *
 * 消息形态同时兼容"带信封"（新）与"裸 payload"（旧），原因见 {@link EventMessages}。
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
            JsonNode messageNode = objectMapper.readTree(message);
            JsonNode payload = EventMessages.isEnvelope(messageNode)
                    ? messageNode.get(EventEnvelope.FIELD_PAYLOAD)
                    : messageNode;
            String submissionId = payload.get("submissionId").asText();
            String verdict = payload.get("verdict").asText();
            String eventId = EventMessages.textOf(messageNode, EventEnvelope.FIELD_EVENT_ID);
            submissionService.applyJudgment(submissionId, eventId, verdict,
                    intOrNull(payload, "score"), intOrNull(payload, "passedCases"), intOrNull(payload, "totalCases"),
                    resolveTraceId(messageNode, payload));
            log.info("Judged consumed eventId={} submissionId={} verdict={}", eventId, submissionId, verdict);
        } catch (SubmissionException.InvalidStatusTransition exception) {
            log.warn("重复判决消息，按幂等吞掉：{}", exception.getMessage());
        } catch (Exception exception) {
            throw new RuntimeException("judged 消息处理失败，触发重试", exception);
        }
    }

    /** traceId 优先取信封（判题链路现在经信封透传），旧格式回落到 payload 内的 traceId。 */
    private static String resolveTraceId(JsonNode messageNode, JsonNode payload) {
        String envelopeTraceId = EventMessages.textOf(messageNode, EventEnvelope.FIELD_TRACE_ID);
        return envelopeTraceId != null ? envelopeTraceId : EventMessages.textOf(payload, "traceId");
    }

    private static Integer intOrNull(JsonNode payload, String fieldName) {
        return payload.hasNonNull(fieldName) ? payload.get(fieldName).asInt() : null;
    }
}
