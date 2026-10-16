package io.lunar.ai.domain;

import io.lunar.ai.domain.dto.CodeReviewAiResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 代码评审的**声明式 AI 接口**（LangChain4j AiServices）。
 *
 * 与手写客户端的区别：这里只声明"给它什么、要它回什么"，以下全部由框架完成——
 *   - 由返回类型 {@link CodeReviewAiResult} 生成 JSON schema，并把它作为输出约束交给模型；
 *   - 把模型输出解析成强类型对象（解析失败抛框架的 Json 异常，由 {@code LlmClient} 翻译成平台错误码）；
 *   - 注入 system 消息（本接口的方法级 {@code @SystemMessage}）。
 *
 * 因此**提示词里不再描述 JSON 形状**：形状的唯一来源是这个 record。两处都写会互相打架，
 * 而且改字段时必然会漏改一处。
 */
public interface CodeReviewAssistant {

    @SystemMessage(PromptLibrary.CODE_REVIEW_SYSTEM)
    CodeReviewAiResult review(@UserMessage String userMessage);
}
