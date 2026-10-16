package io.lunar.ai.domain;

/**
 * AI 作业类型。
 *
 * 取值同时用作 finding 的 {@code finding_type}（不再单独维护一套枚举）：两套清单并存只会漂移，
 * 而"作业产出对应类型的结论"本来就是一一对应的关系。
 */
public enum AiJobType {

    /** 代码评审与讲解：读提交源码 + 题面 + 判题结果，给出错因与改进建议。 */
    CODE_REVIEW,

    /** 出题草稿：生成题面/样例/测试点草案，落草稿表等待教师确认。 */
    PROBLEM_DRAFT
}
