package io.lunar.analytics.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lunar.analytics.domain.JudgedFactService;
import io.lunar.common.event.EventEnvelope;
import io.lunar.common.event.EventMessages;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 消费 lunar-submission-judged：把判决投影成提交事实（一行一次提交的终态）。
 *
 * 消息形态同时兼容"带信封"（新）与"裸 payload"（旧），见 {@link EventMessages}；
 * 裸 payload 没有 eventId，幂等退化为事实表按 submission_id 收敛。
 *
 * 事务加在消费者方法上：闸门登记与事实写入必须一起提交或一起回滚，
 * 否则会出现"登记了已处理、事实没落库"且消息已被 ack 的丢失路径。
 */
@Component
@RocketMQMessageListener(topic = "lunar-submission-judged", consumerGroup = "analytics_judged_group")
public class JudgedFactConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(JudgedFactConsumer.class);

    /** 消费组名，与 {@code @RocketMQMessageListener} 保持一致：闸门表据此区分同一事件的不同消费方。 */
    private static final String CONSUMER = "analytics_judged_group";

    private final JudgedFactService judgedFactService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JudgedFactConsumer(JudgedFactService judgedFactService) {
        this.judgedFactService = judgedFactService;
    }

    @Override
    @Transactional
    public void onMessage(String message) {
        try {
            JsonNode messageNode = EventMessages.parse(objectMapper, message);
            JsonNode payload = EventMessages.payloadOf(objectMapper, message);
            String eventId = EventMessages.textOf(messageNode, EventEnvelope.FIELD_EVENT_ID);
            String submissionId = EventValues.readUuid(payload, "submissionId");
            String contestId = EventValues.readUuid(payload, "contestId");
            String studentId = EventValues.readUuid(payload, "studentId");
            if (submissionId == null || contestId == null || studentId == null) {
                // 缺任一维度都无法归属到"竞赛×学生"上，重投也不会补齐：丢弃并告警
                log.warn("judged 事件缺少关键字段（submissionId/contestId/studentId），丢弃：eventId={}", eventId);
                return;
            }
            Instant judgedAt = resolveJudgedAt(messageNode, payload);
            String verdict = EventText.read(payload, "verdict");
            boolean projected = judgedFactService.project(eventId, CONSUMER, submissionId, contestId,
                    EventValues.readUuid(payload, "problemId"),
                    EventValues.readUuid(payload, "problemVersionId"),
                    studentId,
                    EventText.read(payload, "language"),
                    verdict,
                    EventValues.readIntOrNull(payload, "score"),
                    EventValues.readIntOrNull(payload, "passedCases"),
                    EventValues.readIntOrNull(payload, "totalCases"),
                    EventValues.readInstant(payload, "submittedAt"),
                    judgedAt);
            if (projected) {
                log.info("提交事实已投影：eventId={} submissionId={} verdict={}", eventId, submissionId, verdict);
            }
        } catch (RuntimeException exception) {
            // 非预期失败（数据库不可达、连接池耗尽等）必须抛出：RocketMQ 重投是这类故障的唯一恢复路径
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("judged 消息处理失败，触发重试", exception);
        }
    }

    /**
     * 判决时间：事件里没有这个字段，取信封的 occurredAt。
     *
     * 为什么不用"本服务收到消息的时刻"：judged 事件先落生产者 Outbox 再异步投递，投递延迟会被算进去，
     * 而使错误的时间戳参与排序（"按判题时间倒序"）会让同一批提交的先后看起来随机。
     * 裸 payload 没有信封，此时只能退化为当前时刻——列为 not null，没有留空的余地。
     */
    private static Instant resolveJudgedAt(JsonNode messageNode, JsonNode payload) {
        Instant occurredAt = EventValues.parseInstant(
                EventMessages.textOf(messageNode, EventEnvelope.FIELD_OCCURRED_AT));
        if (occurredAt != null) {
            return occurredAt;
        }
        Instant reportedAt = EventValues.readInstant(payload, "judgedAt");
        return reportedAt == null ? Instant.now() : reportedAt;
    }
}
