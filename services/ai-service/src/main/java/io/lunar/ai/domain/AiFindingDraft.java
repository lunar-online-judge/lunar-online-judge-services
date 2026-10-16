package io.lunar.ai.domain;

/**
 * 处理器的产出（尚未落库的结论内容）。
 *
 * 刻意不含 findingType / model / promptVersion：这些由作业本身决定（类型来自 jobType、
 * 模型与提示词版本来自配置），让处理器去填只会多一处可能与事实不一致的来源。
 *
 * {@code detailJson} 是结构化明细（字段足以支撑人工复核，例如错因分类、相似度分解、引用到的证据 ID）。
 */
public record AiFindingDraft(String severity, Integer score, String summary, String detailJson) {
}
