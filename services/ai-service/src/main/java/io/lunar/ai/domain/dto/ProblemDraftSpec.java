package io.lunar.ai.domain.dto;

/**
 * 出题作业的输入（存 ai_jobs.input_payload，由入队接口写入 JSON）。
 *
 * 是领域类型而不是直接复用 api.dto 的请求体：请求体是对外契约（字段会随接口演进），
 * 而这里是**已落库的历史输入**——作业可能几小时后才被执行，解析的必须是当初存下来的形状。
 */
public record ProblemDraftSpec(String requirement, String difficulty, String languageHint) {
}
