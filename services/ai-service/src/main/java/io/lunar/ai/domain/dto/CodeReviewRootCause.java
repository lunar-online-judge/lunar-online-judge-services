package io.lunar.ai.domain.dto;

/**
 * 代码评审中的单条错因。
 *
 * {@code category} 用自由字符串而不是 Java 枚举：枚举会进入 JSON schema 的 enum 约束，
 * 一旦模型给出枚举外的分类（例如"并发"），整个结构化输出会解析失败、整个作业白跑一次；
 * 而错因分类只是给人看的展示维度，不该有这种杀伤力。取值规范见提示词。
 */
public record CodeReviewRootCause(String category, String explanation) {
}
