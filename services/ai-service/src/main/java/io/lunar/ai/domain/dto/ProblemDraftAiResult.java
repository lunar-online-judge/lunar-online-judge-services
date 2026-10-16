package io.lunar.ai.domain.dto;

import java.util.List;

/**
 * 出题草稿的模型产出（LangChain4j 结构化输出的目标类型）。
 *
 * 字段名即 JSON schema 属性名，是输出契约的唯一来源——提示词里不重复描述（见 {@code ProblemDraftAssistant}）。
 *
 * 刻意**不含测试点的期望输出之外的任何执行信息**（不做 checker、不给部分分规则）：
 * 那些属于 problem-service 的既有模型，草稿只需要能被教师读懂并手工落进题库。
 */
public record ProblemDraftAiResult(String title,
                                   String statement,
                                   String sampleInput,
                                   String sampleOutput,
                                   List<ProblemDraftTestcase> testcases,
                                   List<String> knowledgeTags,
                                   String difficulty,
                                   Integer timeLimitMs,
                                   Integer memoryLimitKb) {
}
