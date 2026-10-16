package io.lunar.analytics.domain;

import io.lunar.analytics.infra.RiskEventFactMapper;
import io.lunar.analytics.mq.EventText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 风险事件事实投影：把 {@code lunar-risk-event-created} 与 {@code lunar-risk-event-reviewed}
 * 投影到同一行（{@code risk_event_facts}）。
 *
 * 为什么创建与审核拆成两个方法而不是一个"合并写入"：两条事件来自不同消费组、各自只携带事实的一半，
 * 合并写入就需要调用方先把缺失字段补成某种占位值，反而会在正常的"创建→审核"顺序上覆盖掉真实字段。
 * 两个方法各管自己那几列，顺序就不影响结果。
 */
@Service
public class RiskEventFactService {

    /**
     * 创建事件先到时无从得知事件类型的兜底值。
     *
     * 正常路径上它不会被写进库：创建事件与该行同源、由 anti-cheat 在同一事务里发出，先到的总是创建事件。
     * 但两个消费组之间没有顺序保证，若审核消息先到，行必须落下来（review_status 已有结论），
     * 而 event_type 是 not null——此时用一个显式的"未知"标记，比编造一个事件类型诚实，也便于事后校准。
     */
    private static final String UNKNOWN_EVENT_TYPE = "UNKNOWN";

    private final RiskEventFactMapper riskEventFactMapper;
    private final ConsumedEventRecorder consumedEventRecorder;

    public RiskEventFactService(RiskEventFactMapper riskEventFactMapper,
                                ConsumedEventRecorder consumedEventRecorder) {
        this.riskEventFactMapper = riskEventFactMapper;
        this.consumedEventRecorder = consumedEventRecorder;
    }

    /**
     * 投影风险事件创建事件。返回 false 表示该 eventId 已处理过，调用方无需做别的事。
     */
    @Transactional
    public boolean projectCreated(String eventId, String consumer, String contestId, String studentId,
                                  String problemId, String eventType, String severity, Instant occurredAt) {
        if (!consumedEventRecorder.markProcessed(eventId, consumer)) {
            return false;
        }
        riskEventFactMapper.insertIfAbsent(eventId, contestId, studentId, problemId,
                EventText.truncate(eventType, EventText.MAX_EVENT_TYPE_LENGTH),
                EventText.truncate(severity, EventText.MAX_SEVERITY_LENGTH),
                occurredAt);
        return true;
    }

    /**
     * 投影风险事件审核流转事件：只改写审核相关三列，不动事件的静态属性。
     *
     * 审核结论里没有事件类型、等级与发生时间，若这里整体覆盖，这些列会被写空；
     * 审核先到的情况下则插入一行只有审核结论的记录——创建事件随后到达时不再改写该行（见 Mapper 注释）。
     *
     * {@code reviewStatus} 由调用方保证非空（缺 newStatus 的消息在消费端就被丢弃），
     * 因为审核状态是这条事件的唯一价值，写不出结论的事件没有投影的必要。
     */
    @Transactional
    public boolean projectReviewed(String eventId, String consumer, String contestId, String studentId,
                                   String reviewStatus, Instant reviewedAt, String reviewedBy) {
        if (!consumedEventRecorder.markProcessed(eventId, consumer)) {
            return false;
        }
        riskEventFactMapper.upsertReview(eventId, contestId, studentId, UNKNOWN_EVENT_TYPE,
                EventText.truncate(reviewStatus, EventText.MAX_VERDICT_LENGTH), reviewedAt, reviewedBy);
        return true;
    }
}
