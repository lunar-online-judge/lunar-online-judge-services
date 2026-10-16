package io.lunar.ai.domain;

import io.lunar.ai.domain.dto.ProblemDraftAiResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 出题草稿的声明式 AI 接口（LangChain4j AiServices）。
 *
 * 与代码评审同理：输出契约由返回类型生成 schema，提示词只写安全规则与业务纪律
 * （题面自洽、数据范围明确、测试点覆盖边界）。
 */
public interface ProblemDraftAssistant {

    @SystemMessage(PromptLibrary.PROBLEM_DRAFT_SYSTEM)
    ProblemDraftAiResult draft(@UserMessage String userMessage);
}
