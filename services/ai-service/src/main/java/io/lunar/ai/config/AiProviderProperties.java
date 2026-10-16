package io.lunar.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI Provider 参数（dataId {@code ai-service.yml}，Nacos 托管）。
 *
 * **本项目只接入云端 API 模型**：各家云厂商共用同一套 OpenAI 兼容协议
 * （{@code /chat/completions}、{@code /embeddings}），因此"切换 Provider"只是
 * 换 base-url + api-key + 模型名，不需要两套客户端实现，也不需要厂商专属 SDK。
 *
 * base-url **刻意不给默认值**（{@code AiModelFactory 的出境检查} 在为空时直接抛
 * {@code IllegalStateException}）：默认值意味着"不配置也能跑"，而"跑起来用的到底是哪个端点"
 * 恰恰是数据出境这件事上最不能猜的一项，必须由部署方显式声明。
 *
 * 用 {@code @ConfigurationProperties} 而不是 {@code @Value}：base-url 与模型名都是运行时可能想改的参数，
 * 而 {@code @Value} 在构造期读进字段等于固化（那样配置中心只剩"改完重启"的价值）。
 * 例外是超时：它建在 RestClient 上，启动期固化，改后需重启——已在 application.yml 注明。
 */
@ConfigurationProperties("ai.provider")
public class AiProviderProperties {

    private AiProviderMode mode = AiProviderMode.CLOUD;

    /**
     * Provider 的 OpenAI 兼容端点基址（如 {@code https://api.deepseek.com/v1}）。
     * **有意留空**：不配置就直接失败（fail closed），绝不回落到某个"碰巧能通"的本地地址。
     */
    private String baseUrl = "";

    private String apiKey = "";

    /** 按所选厂商的模型名填：默认值只是中性占位，实际取值应由配置中心 / 环境变量给定。 */
    private String chatModel = "gpt-4o-mini";

    private int connectTimeoutSeconds = 10;

    private int readTimeoutSeconds = 120;

    private int maxTokens = 2048;

    private double temperature = 0.2;

    public AiProviderMode getMode() {
        return mode;
    }

    public void setMode(AiProviderMode mode) {
        this.mode = mode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
