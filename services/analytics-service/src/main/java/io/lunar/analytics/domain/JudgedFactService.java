package io.lunar.analytics.domain;

import io.lunar.analytics.infra.SubmissionFactMapper;
import io.lunar.analytics.mq.EventText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 提交事实投影：把 {@code lunar-submission-judged} 投影到 {@code submission_facts}。
 *
 * 与其它分析投影的差别在于这条链路的**两个生产者**：judge 直发（判决先落 judge 的 Outbox 再投递）
 * 与 submission 内部回写端点（补偿重放）。两者送达顺序不确定，因此这里只做"按 submission_id 收敛"，
 * 不做"只补空字段"——等到两路都到齐，行的内容就是两条消息的合成结果。
 */
@Service
public class JudgedFactService {

    private final SubmissionFactMapper submissionFactMapper;
    private final ConsumedEventRecorder consumedEventRecorder;

    public JudgedFactService(SubmissionFactMapper submissionFactMapper,
                             ConsumedEventRecorder consumedEventRecorder) {
        this.submissionFactMapper = submissionFactMapper;
        this.consumedEventRecorder = consumedEventRecorder;
    }

    /**
     * 投影一次判决。返回 false 表示该 eventId 已处理过（本次不做任何写入）。
     *
     * {@code judgedAt} 由调用方保证非空：列是 not null，而判决事件本身不携带"引擎何时出结果"，
     * 消费者那边用事件信封的 occurredAt 兜底。
     */
    @Transactional
    public boolean project(String eventId, String consumer, String submissionId, String contestId,
                           String problemId, String problemVersionId, String studentId, String language,
                           String verdict, Integer score, Integer passedCases, Integer totalCases,
                           Instant submittedAt, Instant judgedAt) {
        if (!consumedEventRecorder.markProcessed(eventId, consumer)) {
            return false;
        }
        submissionFactMapper.upsert(submissionId, eventId, contestId, problemId, problemVersionId, studentId,
                EventText.truncate(language, EventText.MAX_LANGUAGE_LENGTH),
                EventText.truncate(verdict, EventText.MAX_VERDICT_LENGTH),
                score, passedCases, totalCases, submittedAt, judgedAt);
        return true;
    }
}
