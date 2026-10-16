package io.lunar.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j 模型工厂：按**当前**配置产出 {@link ChatModel}。
 *
 * 为什么不让它当单例 Bean：ai-service 的 Provider 参数（base-url、模型名、温度）是 Nacos 托管的
 * 运行时可变项，"改完即生效"是这个仓库对托管配置的承诺；一旦在启动期固化成单例 Bean，
 * 配置中心就只剩"改完重启"的价值。因此这里按**配置指纹**缓存模型实例：
 * 配置不变时复用（不重复建 HTTP 客户端），配置变了自然换新实例。
 *
 * 缓存是有界的：指纹只随配置变化增长，而托管配置的取值组合在实践中是个位数。
 *
 * 重试策略：{@code maxRetries(0)} —— 重试归 ai_jobs 的 attempts/退避所有。
 * 让框架在内部悄悄重试会让"尝试次数"失真，日志里看到的失败次数也就不可信了。
 */
@Component
public class AiModelFactory implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AiModelFactory.class);

    /**
     * 自建的 OpenAI 兼容网关通常不校验密钥，但客户端要求必须给一个值；
     * 用这个占位值而不是空串，避免把"内网网关没配密钥"变成构造期异常。
     */
    private static final String NO_KEY_PLACEHOLDER = "not-needed";

    private final AiProviderProperties properties;
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();

    public AiModelFactory(AiProviderProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动期就检查一次：配置错（缺 base-url、公网地址未授权、外部端点缺 key）必须让服务**起不来**，
     * 而不是等第一位老师点了"生成评审"才发现——那时学生代码已经在路上。
     * 每次构建模型前还会再查一次：base-url 是可热更新配置，只在启动期检查会被运行期改地址绕过。
     */
    @Override
    public void afterPropertiesSet() {
        requireEgressApproved();
    }

    public ChatModel chatModel() {
        return chatModels.computeIfAbsent(chatSignature(), signature -> {
            requireEgressApproved();
            log.info("按当前配置构建 ChatModel：mode={} host={} model={}",
                    properties.getMode(), hostOf(properties.getBaseUrl()), properties.getChatModel());
            return buildChatModel();
        });
    }

    private ChatModel buildChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(effectiveApiKey())
                .modelName(properties.getChatModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .maxRetries(0)
                // 不记录请求/响应体：题面与源码会因此进入日志，而仓库明确要求学生代码不落日志
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 出境门禁（fail closed）：把学生源码发给外部模型是**合规问题**，不能靠"配置写对了"来保证。
     *
     * 三条规则：
     *   1. base-url 必填——没有默认端点，配错时在第一次调用就给出明确错误；
     *   2. 指向**外部地址**时，必须显式设置环境变量 {@code AI_EGRESS_APPROVED=true}（只认环境变量，
     *      配置中心改不动它——否则一次配置误改就能把学生代码发到任意地址）；
     *   3. 外部地址必须带 api-key（内网自建网关允许不带，用占位值）。
     *
     * 每次构建模型前都会执行：base-url 是可热更新的配置，只在启动期检查会被"运行期改成公网地址"绕过。
     */
    private void requireEgressApproved() {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("ai.provider.base-url 未配置：模型调用无法进行");
        }
        String host = hostOf(baseUrl);
        if (isInternalHost(host)) {
            return;
        }
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("AI_EGRESS_APPROVED", "false"))) {
            throw new IllegalStateException("ai.provider.base-url 指向外部地址（" + host
                    + "）：把学生代码发出去需要显式授权，请设置环境变量 AI_EGRESS_APPROVED=true");
        }
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("外部模型端点必须配置 ai.provider.api-key");
        }
    }

    /** 内网/本机地址无需出境授权：自建网关通常不校验密钥，也不涉及数据外发合规。 */
    private static boolean isInternalHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return host.equals("localhost") || host.equals("::1") || host.startsWith("127.")
                || host.startsWith("10.") || host.startsWith("192.168.")
                || host.endsWith(".internal") || host.endsWith(".local");
    }

    private String effectiveApiKey() {
        String apiKey = properties.getApiKey();
        return apiKey == null || apiKey.isBlank() ? NO_KEY_PLACEHOLDER : apiKey;
    }

    /** 只参与缓存键与日志，不落到日志里的密钥：用哈希避免明文密钥出现在堆栈/转储里。 */
    private String chatSignature() {
        return String.join("|", properties.getMode().name(), properties.getBaseUrl(), properties.getChatModel(),
                String.valueOf(properties.getTemperature()), String.valueOf(properties.getMaxTokens()),
                String.valueOf(properties.getApiKey() == null ? 0 : properties.getApiKey().hashCode()));
    }

    /** 取 URL 的主机名用于日志（不打印完整地址，避免把路径里的租户/密钥片段写进日志）。 */
    public static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            return java.net.URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException exception) {
            return "invalid";
        }
    }
}
