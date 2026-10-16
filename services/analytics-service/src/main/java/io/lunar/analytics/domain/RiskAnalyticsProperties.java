package io.lunar.analytics.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 风险聚合的参数（{@code analytics.risk.*}）。
 *
 * 权重为什么外置成配置：可疑度公式是**产品口径**，而产品口径在本仓库尚未确认。
 * 把它写成常量会让"改一下权重看看排序"变成一次发版；写成配置则由运维/产品直接调，
 * 且公式本身仍只有一处（RiskEventFactMapper 的那条 SQL）。
 */
@ConfigurationProperties("analytics.risk")
public class RiskAnalyticsProperties {

    /** 热力图时间桶宽度（分钟）。 */
    private int heatmapBucketMinutes = 30;

    /** 热力图最多返回的格子数：截断方向是"最近优先"。 */
    private int heatmapMaxBuckets = 96;

    /** HIGH 级事件的权重。 */
    private int weightHigh = 5;

    /** 已确认（CONFIRMED）事件的权重：审核结论是人工判断，权重要能压过机器规则。 */
    private int weightConfirmed = 10;

    /** 已看到未处置（ACKNOWLEDGED）事件的权重。 */
    private int weightAcknowledged = 3;

    /** 待审核（OPEN）事件的权重。 */
    private int weightPending = 1;

    public int getHeatmapBucketMinutes() {
        return heatmapBucketMinutes;
    }

    public void setHeatmapBucketMinutes(int heatmapBucketMinutes) {
        this.heatmapBucketMinutes = heatmapBucketMinutes;
    }

    public int getHeatmapMaxBuckets() {
        return heatmapMaxBuckets;
    }

    public void setHeatmapMaxBuckets(int heatmapMaxBuckets) {
        this.heatmapMaxBuckets = heatmapMaxBuckets;
    }

    public int getWeightHigh() {
        return weightHigh;
    }

    public void setWeightHigh(int weightHigh) {
        this.weightHigh = weightHigh;
    }

    public int getWeightConfirmed() {
        return weightConfirmed;
    }

    public void setWeightConfirmed(int weightConfirmed) {
        this.weightConfirmed = weightConfirmed;
    }

    public int getWeightAcknowledged() {
        return weightAcknowledged;
    }

    public void setWeightAcknowledged(int weightAcknowledged) {
        this.weightAcknowledged = weightAcknowledged;
    }

    public int getWeightPending() {
        return weightPending;
    }

    public void setWeightPending(int weightPending) {
        this.weightPending = weightPending;
    }
}
