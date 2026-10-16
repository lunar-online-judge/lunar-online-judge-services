package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * analytics_schema.contest_refs 竞赛参照数据缓存。
 *
 * 为什么缓存而不是每次查询回源：事实表只存 contestId 这类标识，聚合结果要展示竞赛名与时间窗，
 * 而聚合是高频读、竞赛元数据是低频写；把它复制到分析侧，查询就不必跨服务调用，也不会被 contest 的抖动拖垮。
 */
@TableName("analytics_schema.contest_refs")
public class ContestRefEntity {

    @TableId(value = "contest_id", type = IdType.INPUT)
    private String contestId;

    private String name;

    private String status;

    private Instant startAt;

    private Instant endAt;

    private String ownerTeacherId;

    private Instant refreshedAt;

    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public String getOwnerTeacherId() { return ownerTeacherId; }
    public void setOwnerTeacherId(String ownerTeacherId) { this.ownerTeacherId = ownerTeacherId; }
    public Instant getRefreshedAt() { return refreshedAt; }
    public void setRefreshedAt(Instant refreshedAt) { this.refreshedAt = refreshedAt; }
}
