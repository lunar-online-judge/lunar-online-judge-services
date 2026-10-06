package io.lunar.contest.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** contest_schema.announcements 公告持久化对象。 */
@TableName("contest_schema.announcements")
public class AnnouncementEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String contestId;

    private String content;

    private Boolean important;

    private Boolean requireConfirmation;

    private String createdBy;

    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getImportant() { return important; }
    public void setImportant(Boolean important) { this.important = important; }
    public Boolean getRequireConfirmation() { return requireConfirmation; }
    public void setRequireConfirmation(Boolean requireConfirmation) { this.requireConfirmation = requireConfirmation; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
