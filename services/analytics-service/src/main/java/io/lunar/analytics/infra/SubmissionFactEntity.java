package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * analytics_schema.submission_facts 提交事实：一行一次提交的终态。
 *
 * 主键取 submissionId 而不是事件 eventId：judged 事件有两个生产者（judge 直发、submission 内部回写），
 * 同一提交会被投递两次但携带不同的 eventId；按提交收敛才让两路殊途同归，按事件收下则会得到两行。
 */
@TableName("analytics_schema.submission_facts")
public class SubmissionFactEntity {

    @TableId(value = "submission_id", type = IdType.INPUT)
    private String submissionId;

    /** 最后一次改写本行的事件 ID：只作追溯用，不承担唯一性。 */
    private String eventId;

    private String contestId;

    private String problemId;

    private String problemVersionId;

    private String studentId;

    private String language;

    private String verdict;

    private Integer score;

    private Integer passedCases;

    private Integer totalCases;

    private Instant submittedAt;

    private Instant judgedAt;

    public String getSubmissionId() { return submissionId; }
    public void setSubmissionId(String submissionId) { this.submissionId = submissionId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getProblemId() { return problemId; }
    public void setProblemId(String problemId) { this.problemId = problemId; }
    public String getProblemVersionId() { return problemVersionId; }
    public void setProblemVersionId(String problemVersionId) { this.problemVersionId = problemVersionId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public Integer getPassedCases() { return passedCases; }
    public void setPassedCases(Integer passedCases) { this.passedCases = passedCases; }
    public Integer getTotalCases() { return totalCases; }
    public void setTotalCases(Integer totalCases) { this.totalCases = totalCases; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getJudgedAt() { return judgedAt; }
    public void setJudgedAt(Instant judgedAt) { this.judgedAt = judgedAt; }
}
