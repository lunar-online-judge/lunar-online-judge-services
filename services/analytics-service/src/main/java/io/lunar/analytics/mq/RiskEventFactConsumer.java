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
 * 消费 lunar-risk-event-created：把风险事件投影成事实行（一行一条事件）。
 *
 * 这条事件的关键字段全部来自内层 payload（anti-cheat 的 writeOutbox 组装），
 * 信封只用于取 eventId——主键必须是创建事件自己的 eventId，审核流转事件才能在同一行上做修正。
 */
@Component
@RocketMQMessageListener(topic = "lunar-risk-event-created", consumerGroup = "analytics_risk_group")
public class RiskEventFactConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(RiskEventFactConsumer.class);

    /** 消费组名，与 {@code @RocketMQMessageListener} 保持一致：闸门表据此区分同一事件的不同消费方。 */
    private static final String CONSUMER = "analytics_risk_group";

    private final RiskEventFactService riskEventFactService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RiskEventFactConsumer(RiskEventFactService riskEventFactService) {
        this.riskEventFactService = riskEventFactService;
    }

    @Override
    @Transactional
    public void onMessage(String message) {
        try {
            JsonNode messageNode = EventMessages.parse(objectMapper, message);
            JsonNode payload = EventMessages.payloadOf(objectMapper, message);
            String envelopeEventId = EventValues.readUuid(messageNode, EventEnvelope.FIELD_EVENT_ID);
            // 旧格式（裸 payload）没有信封，退回 anti-cheat 写入 payload 的 riskEventId
            String eventId = envelopeEventId == null
                    ? EventValues.readUuid(payload, "riskEventId") : envelopeEventId;
            String contestId = EventValues.readUuid(payload, "contestId");
            String studentId = EventValues.readUuid(payload, "studentId");
            String eventType = EventText.read(payload, "eventType");
            if (eventId == null || contestId == null || studentId == null || eventType == null) {
                log.warn("风险事件缺少关键字段（eventId/contestId/studentId/eventType），丢弃：eventId={}", eventId);
                return;
            }
            boolean projected = riskEventFactService.projectCreated(eventId, CONSUMER, contestId, studentId,
                    EventValues.readUuid(payload, "problemId"),
                    eventType,
                    EventText.read(payload, "severity"),
                    resolveOccurredAt(messageNode, payload));
            if (projected) {
                log.info("风险事件事实已投影：eventId={} contestId={} eventType={}", eventId, contestId, eventType);
            }
        } catch (RuntimeException exception) {
            // 非预期失败（数据库不可达、连接池耗尽等）必须抛出：RocketMQ 重投是这类故障的唯一恢复路径
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("风险事件消息处理失败，触发重试", exception);
        }
    }

    /**
     * 事件发生时间：优先取 payload 里的 occurredAt（anti-cheat 记的是客户端上报的时刻），
     * 缺失时退回信封的 occurredAt——两者都不可用时置空，列允许 null，不编造时间。
     */
    private static Instant resolveOccurredAt(JsonNode messageNode, JsonNode payload) {
        Instant reportedAt = EventValues.readInstant(payload, "occurredAt");
        if (reportedAt != null) {
            return reportedAt;
        }
        return EventValues.parseInstant(EventMessages.textOf(messageNode, EventEnvelope.FIELD_OCCURRED_AT));
    }
}
