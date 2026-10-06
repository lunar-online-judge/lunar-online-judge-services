package io.lunar.contest.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** contest_schema.contest_candidates 报名关系持久化对象。 */
@TableName("contest_schema.contest_candidates")
public class CandidateEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String contestId;

    private String studentId;

    private String studentNoSnapshot;

    private String usernameSnapshot;

    private String displayNameSnapshot;

    private String addedByTeacherId;

    private String status;

    private Instant enrolledAt;

    private Instant removedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getStudentNoSnapshot() { return studentNoSnapshot; }
    public void setStudentNoSnapshot(String studentNoSnapshot) { this.studentNoSnapshot = studentNoSnapshot; }
    public String getAddedByTeacherId() { return addedByTeacherId; }
    public void setAddedByTeacherId(String addedByTeacherId) { this.addedByTeacherId = addedByTeacherId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(Instant enrolledAt) { this.enrolledAt = enrolledAt; }
    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
    public String getUsernameSnapshot() { return usernameSnapshot; }
    public void setUsernameSnapshot(String usernameSnapshot) { this.usernameSnapshot = usernameSnapshot; }
    public String getDisplayNameSnapshot() { return displayNameSnapshot; }
    public void setDisplayNameSnapshot(String displayNameSnapshot) { this.displayNameSnapshot = displayNameSnapshot; }
}
