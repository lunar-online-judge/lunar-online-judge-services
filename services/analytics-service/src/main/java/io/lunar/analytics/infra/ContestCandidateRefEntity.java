package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * analytics_schema.contest_candidate_refs 竞赛-考生参照数据缓存。
 *
 * 为什么要单独缓存考生身份：排行榜与监考视图必须展示学号/姓名，而事实表里只有 studentId；
 * 若在查询时逐个回源 auth-service，一次榜单渲染会放大出成百上千次服务调用。
 *
 * 表上的主键是 (contest_id, student_id) 复合键；实体只标 contestId，理由同 ContestProblemRefEntity。
 */
@TableName("analytics_schema.contest_candidate_refs")
public class ContestCandidateRefEntity {

    @TableId(value = "contest_id", type = IdType.INPUT)
    private String contestId;

    private String studentId;

    private String studentNo;

    private String username;

    private String displayName;

    private String enrollmentStatus;

    private Instant refreshedAt;

    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEnrollmentStatus() { return enrollmentStatus; }
    public void setEnrollmentStatus(String enrollmentStatus) { this.enrollmentStatus = enrollmentStatus; }
    public Instant getRefreshedAt() { return refreshedAt; }
    public void setRefreshedAt(Instant refreshedAt) { this.refreshedAt = refreshedAt; }
}
