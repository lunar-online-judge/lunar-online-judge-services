package io.lunar.analytics.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lunar.analytics.domain.RiskEventFactService;
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
 * 消费 lunar-risk-event-reviewed：把人工审核的流转结论投影到已存在的风险事件事实行上。
 *
 * 为什么必须单独有这条消费者：风险事件只在**创建**时进 Outbox，若分析侧只消费 created，
 * 所有事件会永远停留在 OPEN，监考视图上的审核结论根本传不出来。
 */
@Component
@RocketMQMessageListener(topic = "lunar-risk-event-reviewed", consumerGroup = "analytics_risk_review_group")
public class RiskReviewFactConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(RiskReviewFactConsumer.class);

    /** 消费组名，与 {@code @RocketMQMessageListener} 保持一致：闸门表据此区分同一事件的不同消费方。 */
    private static final String CONSUMER = "analytics_risk_review_group";

    private final RiskEventFactService riskEventFactService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RiskReviewFactConsumer(RiskEventFactService riskEventFactService) {
        this.riskEventFactService = riskEventFactService;
    }

    @Override
    @Transactional
    public void onMessage(String message) {
        try {
            JsonNode messageNode = EventMessages.parse(objectMapper, message);
            JsonNode payload = EventMessages.payloadOf(objectMapper, message);
            String envelopeEventId = EventValues.readUuid(messageNode, EventEnvelope.FIELD_EVENT_ID);
            String eventId = envelopeEventId == null
                    ? EventValues.readUuid(payload, "riskEventId") : envelopeEventId;
            String contestId = EventValues.readUuid(payload, "contestId");
            String studentId = EventValues.readUuid(payload, "studentId");
            String newStatus = EventText.read(payload, "newStatus");
            if (eventId == null || contestId == null || studentId == null || newStatus == null) {
                log.warn("风险审核事件缺少关键字段（eventId/contestId/studentId/newStatus），丢弃：eventId={}",
                        eventId);
                return;
            }
            boolean projected = riskEventFactService.projectReviewed(eventId, CONSUMER, contestId, studentId,
                    newStatus,
                    resolveReviewedAt(messageNode, payload),
                    EventValues.readUuid(payload, "reviewedBy"));
            if (projected) {
                log.info("风险审核结论已投影：eventId={} newStatus={} previousStatus={}",
                        eventId, newStatus, EventText.read(payload, "previousStatus"));
            }
        } catch (RuntimeException exception) {
            // 非预期失败（数据库不可达、连接池耗尽等）必须抛出：RocketMQ 重投是这类故障的唯一恢复路径
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("风险审核消息处理失败，触发重试", exception);
        }
    }

    /**
     * 审核时间：anti-cheat 在 payload 里带了 reviewedAt，缺失时退回信封的 occurredAt（审核事件与状态更新同事务，
     * 两者的时刻本就一致，只在毫秒级有差别），都取不到才置空——列允许 null，不编造时间。
     */
    private static Instant resolveReviewedAt(JsonNode messageNode, JsonNode payload) {
        Instant reviewedAt = EventValues.readInstant(payload, "reviewedAt");
        if (reviewedAt != null) {
            return reviewedAt;
        }
        return EventValues.parseInstant(EventMessages.textOf(messageNode, EventEnvelope.FIELD_OCCURRED_AT));
    }
}
