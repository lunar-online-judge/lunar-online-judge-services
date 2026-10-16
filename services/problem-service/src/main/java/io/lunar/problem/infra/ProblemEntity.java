package io.lunar.problem.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** problem_schema.problems 题目主表。 */
@TableName("problem_schema.problems")
public class ProblemEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String title;

    private String currentVersionId;

    private String createdBy;

    /** 有权管理该题目的老师（BE-01 资源范围）；为空则仅 ADMIN 可见。 */
    private String ownerTeacherId;

    private Instant createdAt;

    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(String currentVersionId) { this.currentVersionId = currentVersionId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getOwnerTeacherId() { return ownerTeacherId; }
    public void setOwnerTeacherId(String ownerTeacherId) { this.ownerTeacherId = ownerTeacherId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}