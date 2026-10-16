package io.lunar.ai.infra;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.service.AiServices;
import io.lunar.ai.config.AiLimitsProperties;
import io.lunar.ai.config.AiModelFactory;
import io.lunar.ai.domain.AiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 与模型 Provider 的唯一交互出口。三件事，每件都有明确理由：
 *
 * ① **构建 AiServices 代理**（{@link #assistant}）：声明式接口 + 结构化输出由 LangChain4j 负责，
 *    业务代码不再手写"拼 JSON 提示词 + 解析 + 校验字段类型"这套东西。
 *
 * ② **向量化**（{@link #embed}）：embedding 与对话是同一 Provider 的两种能力，
 *    放在一起便于统一处理"未启用就直接拒绝"。
 *
 * ③ **异常翻译**（{@link #call}）：把 LangChain4j 的 {@code dev.langchain4j.exception.*}
 *    映射成本平台已登记的错误码（AI_PROVIDER_UNAVAILABLE / AI_PROVIDER_REJECTED / AI_INVALID_MODEL_OUTPUT）。
 *    这层存在的意义是：上层（作业 worker）只认平台错误码，不认框架异常——
 *    否则框架升级换了异常类型，作业的 error_code 就会跟着漂移。
 *
 * 注意"模型输出校验"的边界：框架能保证**结构**（字段存在、类型正确），
 * 但保证不了**业务含义**（这个分数在我们这里是否合法、理由是否引用了真实记录），
 * 后者由 {@code ModelOutputParser} 与各处理器负责。
 */
@Component
public class LlmClient {

    private final AiModelFactory modelFactory;
    private final AiLimitsProperties limitsProperties;

    public LlmClient(AiModelFactory modelFactory, AiLimitsProperties limitsProperties) {
        this.modelFactory = modelFactory;
        this.limitsProperties = limitsProperties;
    }

    /** 取声明式 AI 接口（AiServices 代理）；模型按当前配置构建，配置改了自然换新模型。 */
    public <T> T assistant(Class<T> assistantInterface) {
        return AiServices.builder(assistantInterface)
                .chatModel(modelFactory.chatModel())
                .build();
    }

    /** 执行一次模型调用：框架异常在此统一翻译成平台错误码。 */
    public <T> T call(Supplier<T> modelCall) {
        try {
            return modelCall.get();
        } catch (LangChain4jException exception) {
            throw mapFailure(exception);
        }
    }

    /** 批量向量化。未启用 embedding 时直接拒绝，而不是偷偷退化成"没有向量也算通过"。 */

    /**
     * 框架异常 → 平台错误码。
     *
     * 分类依据是"重试有没有意义"：超时/服务器错误/域名解析失败属瞬时故障（503，值得重试）；
     * 鉴权、模型不存在、请求非法、模型不支持该能力属配置问题（502，重试无用，要人去改配置）；
     * 解析失败说明模型没按结构输出（502 且单独一个码，便于统计"模型不听话"的比例）。
     */
    private static AiException mapFailure(LangChain4jException exception) {
        String failureName = exception.getClass().getSimpleName();
        if (exception instanceof TimeoutException
                || exception instanceof UnresolvedModelServerException
                || exception instanceof InternalServerException) {
            return new AiException.ProviderUnavailable("Provider 不可达或超时（" + failureName + "）");
        }
        if (exception instanceof ContentFilteredException) {
            return new AiException.ProviderRejected("Provider 按内容策略拒绝了该请求");
        }
        if (exception instanceof AuthenticationException
                || exception instanceof ModelNotFoundException
                || exception instanceof InvalidRequestException
                || exception instanceof RateLimitException
                || exception instanceof UnsupportedFeatureException
                || exception instanceof HttpException) {
            // RateLimit 也归这里：本平台自己的配额用 AI_QUOTA_EXCEEDED，
            // Provider 侧限流属"配置/额度需要人看"的外部约束，重试无意义
            return new AiException.ProviderRejected("Provider 拒绝请求（" + failureName + "）：模型名/密钥/额度需要检查");
        }
        return new AiException.InvalidModelOutput("模型输出不符合约定结构（" + failureName + "）");
    }

    private static double[] toDoubleArray(float[] vector) {
        double[] result = new double[vector.length];
        for (int index = 0; index < vector.length; index++) {
            result[index] = vector[index];
        }
        return result;
    }
}
