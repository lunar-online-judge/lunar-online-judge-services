package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * analytics_schema.student_contest_scores 成绩与排名读模型。
 *
 * 表上的主键是 (contest_id, student_id) 复合键；实体只能标一个 @TableId，这里标 contestId。
 * 由此带来的限制：不能按主键做单行增删（会误伤同一竞赛的其他学生），
 * 本表的写入一律是"按竞赛整批替换"（见 StudentContestScoreMapper 的删除+写入），不依赖按主键操作。
 */
@TableName("analytics_schema.student_contest_scores")
public class StudentContestScoreEntity {

    @TableId(value = "contest_id", type = IdType.INPUT)
    private String contestId;

    private String studentId;

    private Integer totalScore;

    private Integer solvedCount;

    private Instant lastEffectiveAt;

    /** 竞赛内名次（1 起）。由重算流程按配置的 tie-break 授予，不在这里推导。 */
    private Integer rank;

    private Instant computedAt;

    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public Integer getTotalScore() { return totalScore; }
    public void setTotalScore(Integer totalScore) { this.totalScore = totalScore; }
    public Integer getSolvedCount() { return solvedCount; }
    public void setSolvedCount(Integer solvedCount) { this.solvedCount = solvedCount; }
    public Instant getLastEffectiveAt() { return lastEffectiveAt; }
    public void setLastEffectiveAt(Instant lastEffectiveAt) { this.lastEffectiveAt = lastEffectiveAt; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
}
