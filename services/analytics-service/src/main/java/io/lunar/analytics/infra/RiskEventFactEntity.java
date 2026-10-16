package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * analytics_schema.risk_event_facts 风险事件事实。
 *
 * 主键取事件 eventId：风险事件是不可变事实，一条只产生一个 eventId；
 * 审核流转事件另发一条消息，但它携带同一个 riskEventId（即 eventId），因此落在同一行上做修正，
 * 不会把一次作弊嫌疑拆成两行。
 */
@TableName("analytics_schema.risk_event_facts")
public class RiskEventFactEntity {

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    private String contestId;

    private String studentId;

    private String problemId;

    private String eventType;

    private String severity;

    private Instant occurredAt;

    private String reviewStatus;

    private Instant reviewedAt;

    private String reviewedBy;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getProblemId() { return problemId; }
    public void setProblemId(String problemId) { this.problemId = problemId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
}
