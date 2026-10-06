package io.lunar.contest.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** contest_schema.announcement_confirms 送达/确认记录。 */
@TableName("contest_schema.announcement_confirms")
public class AnnouncementConfirmEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String announcementId;

    private String studentId;

    private Instant deliveredAt;

    private Instant confirmedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(String announcementId) { this.announcementId = announcementId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
}
