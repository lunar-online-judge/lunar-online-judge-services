package io.lunar.ai.domain.dto;

import java.util.List;

/**
 * 代码评审的模型产出（LangChain4j 结构化输出的目标类型）。
 *
 * 字段名即 JSON schema 的属性名——**它就是输出契约的唯一来源**，
 * 所以提示词里不再重复描述字段（见 {@code CodeReviewAssistant} 的说明）。
 *
 * {@code hintOnly} 让模型显式声明"我只给了提示、没给完整解法"，
 * 便于人工抽查时快速判断这条评审是否符合"不代做"的产品纪律。
 */
public record CodeReviewAiResult(String verdictExplanation,
                                 List<CodeReviewRootCause> rootCauses,
                                 List<String> improvements,
                                 String complexityNote,
                                 Boolean hintOnly) {
}
