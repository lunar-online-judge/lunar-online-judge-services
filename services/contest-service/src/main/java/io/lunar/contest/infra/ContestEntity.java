package io.lunar.contest.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** contest_schema.contests 持久化对象。id 由应用侧生成（uuid 主键无库端默认值）。 */
@TableName("contest_schema.contests")
public class ContestEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String name;

    private String description;

    private String status;

    private Instant startAt;

    private Instant endAt;

    private String createdBy;

    /** 有权管理该考试的老师（BE-01 资源范围）；为空则仅 ADMIN 可见。 */
    private String ownerTeacherId;

    private Instant createdAt;

    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getOwnerTeacherId() { return ownerTeacherId; }
    public void setOwnerTeacherId(String ownerTeacherId) { this.ownerTeacherId = ownerTeacherId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
