package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * analytics_schema.contest_problem_refs 竞赛-题目参照数据缓存。
 *
 * 表上的主键是 (contest_id, problem_id) 复合键；实体只能标一个 @TableId，这里标 contestId
 * 仅为了让 MyBatis-Plus 有主键可用，删除/更新这类按主键的单行操作不适用于本表——
 * 参照数据整体由定时任务刷新，不做单行增删。
 */
@TableName("analytics_schema.contest_problem_refs")
public class ContestProblemRefEntity {

    @TableId(value = "contest_id", type = IdType.INPUT)
    private String contestId;

    private String problemId;

    private String problemVersionId;

    /** 竞赛内的题目顺序：榜单与题目列表要按命题人编排的顺序展示。 */
    private Integer sortOrder;

    private Instant refreshedAt;

    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getProblemId() { return problemId; }
    public void setProblemId(String problemId) { this.problemId = problemId; }
    public String getProblemVersionId() { return problemVersionId; }
    public void setProblemVersionId(String problemVersionId) { this.problemVersionId = problemVersionId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Instant getRefreshedAt() { return refreshedAt; }
    public void setRefreshedAt(Instant refreshedAt) { this.refreshedAt = refreshedAt; }
}
