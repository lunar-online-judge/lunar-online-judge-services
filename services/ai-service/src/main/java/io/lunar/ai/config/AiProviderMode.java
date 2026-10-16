package io.lunar.ai.config;

/**
 * Provider 部署形态。
 *
 * 刻意不参与协议选择——各家 Provider **共用** OpenAI 兼容协议，这个枚举只用于日志、健康检查
 * 与运维语义（"现在把学生源码发去哪儿了"）。真正决定数据是否出境的判据是 base-url 的地址，
 * 见 {@link AiModelFactory 的出境检查}。
 */
public enum AiProviderMode {

    /**
     * 自建的内网 OpenAI 兼容网关：代码不出本机/校园网。
     *
     * **仅此一种用途**——本项目不再支持本地 Ollama 这类跑在本机的模型运行时，
     * 它同样要显式配置 base-url，且必须指向内网地址。
     */
    LOCAL,

    /** 云厂商（OpenAI 兼容：DeepSeek 等）：数据出境，需显式授权。 */
    CLOUD
}
