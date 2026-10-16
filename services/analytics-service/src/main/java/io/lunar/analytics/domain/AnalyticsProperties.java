package io.lunar.analytics.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分析读模型的服务级开关（{@code analytics.*}）。
 *
 * 前缀只到 {@code analytics} 而不是 {@code analytics.score}：配置键名按产品口径写成
 * {@code analytics.score-visibility}，属性类跟着键名走，运维在 Nacos 里看到的键与文档一致。
 * 同前缀下的其它段（ranking / refresh / risk）由各自的属性类绑定，互不干扰。
 */
@ConfigurationProperties("analytics")
public class AnalyticsProperties {

    /**
     * 成绩对考生的可见范围；默认只能看自己那一行。
     * **待确认**（docs/exam-process.md §10-6）：考试结束后的放开策略未定，因此不建模。
     */
    private ScoreVisibility scoreVisibility = ScoreVisibility.OWN_ONLY;

    public ScoreVisibility getScoreVisibility() {
        return scoreVisibility;
    }

    public void setScoreVisibility(ScoreVisibility scoreVisibility) {
        this.scoreVisibility = scoreVisibility;
    }
}
