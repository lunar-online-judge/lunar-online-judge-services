package io.lunar.ai.domain.dto;

/**
 * Provider 运行态视图（运维用）。
 *
 * **不含 api-key**：只回显"数据发去哪、用的哪个模型"，这正是运维需要确认的两件事
 * （"现在到底有没有把学生代码发给外部地址"），而密钥回显没有任何正当用途。
 */
public record AiProviderView(String mode,
                            String baseUrlHost,
                            String chatModel) {
}
