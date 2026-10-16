package io.lunar.analytics.domain;

/**
 * 成绩对考生的可见范围（{@code analytics.score-visibility}）。
 *
 * 取值刻意只有两个：docs/exam-process.md §10-6 只说"由考试配置控制"，具体配置项尚未落表，
 * 因此这里只表达当前唯一能确定的两态。**"考试结束后是否放开全部可见"没有建模**——
 * 它依赖同一节里尚未实现的成绩状态机（PROVISIONAL → UNDER_REVIEW → FINAL），
 * 现在猜一个取值，等状态机落地时一定会被推翻。
 */
public enum ScoreVisibility {

    /** 考生只能看自己那一行（当前默认）。 */
    OWN_ONLY,

    /** 考生看不到任何成绩（连自己那一行也不返回，响应里只有 visibility=HIDDEN）。 */
    HIDDEN
}
