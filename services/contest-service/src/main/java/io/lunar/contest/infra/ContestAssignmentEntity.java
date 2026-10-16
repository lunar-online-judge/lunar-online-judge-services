package io.lunar.contest.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** contest_schema.contest_assignments：按学生的随机题目分配（docs/exam-process.md §3）。 */
@TableName("contest_schema.contest_assignments")
public class ContestAssignmentEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String contestId;

    private String studentId;

    private String problemId;

    /** 分配时锁定的题目版本：判题与取测试点都用它。 */
    private String problemVersionId;

    /** 该学生本次分配的随机种子：同一 (考试, 学生) 恒定，保证可复现。 */
    private Long randomSeed;

    /** 分配版本：题目集合发生变化时递增，便于区分"当时用的是哪一版分配"。 */
    private Integer assignmentVersion;

    private Instant assignedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getProblemId() { return problemId; }
    public void setProblemId(String problemId) { this.problemId = problemId; }
    public String getProblemVersionId() { return problemVersionId; }
    public void setProblemVersionId(String problemVersionId) { this.problemVersionId = problemVersionId; }
    public Long getRandomSeed() { return randomSeed; }
    public void setRandomSeed(Long randomSeed) { this.randomSeed = randomSeed; }
    public Integer getAssignmentVersion() { return assignmentVersion; }
    public void setAssignmentVersion(Integer assignmentVersion) { this.assignmentVersion = assignmentVersion; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
}
