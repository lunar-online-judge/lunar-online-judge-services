package io.lunar.ai.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * ai_schema.problem_drafts 出题草稿持久化对象。
 *
 * 这张表是"AI 产出"与"题库事实"之间的缓冲：AI 只写这里，教师确认后才由 problem-service
 * 的既有接口落库——因此"发布后版本不可变""发布前至少 1 个测试点"等既有不变量都不需要为 AI 让路。
 */
@TableName("ai_schema.problem_drafts")
public class ProblemDraftEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String jobId;

    private String contestId;

    private String title;

    private String statement;

    private String sampleInput;

    private String sampleOutput;

    private String suggestedTestcases;

    private String knowledgeTags;

    private String difficulty;

    private String languageHint;

    private String reviewStatus;

    private String reviewedBy;

    private Instant reviewedAt;

    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }
    public String getSampleInput() { return sampleInput; }
    public void setSampleInput(String sampleInput) { this.sampleInput = sampleInput; }
    public String getSampleOutput() { return sampleOutput; }
    public void setSampleOutput(String sampleOutput) { this.sampleOutput = sampleOutput; }
    public String getSuggestedTestcases() { return suggestedTestcases; }
    public void setSuggestedTestcases(String suggestedTestcases) { this.suggestedTestcases = suggestedTestcases; }
    public String getKnowledgeTags() { return knowledgeTags; }
    public void setKnowledgeTags(String knowledgeTags) { this.knowledgeTags = knowledgeTags; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getLanguageHint() { return languageHint; }
    public void setLanguageHint(String languageHint) { this.languageHint = languageHint; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
