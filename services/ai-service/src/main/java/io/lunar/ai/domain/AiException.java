package io.lunar.ai.domain;

import io.lunar.common.api.ApiException;

/**
 * AI 域异常：携带 docs/api.md 错误码注册表的稳定 code 与 HTTP 状态，由全局异常处理器统一转换。
 *
 * 与仓库既有约定一致（AuthException / ContestException 等同样把子类声明在基类内）：
 * 这些子类只承载"码 + 状态 + 默认文案"，放在一起才看得清整套错误语义。
 */
public class AiException extends ApiException {

    /**
     * 错误码常量：让"作业层需要按码记失败"与"抛异常"两条路径共用同一个字面量。
     * 否则 {@code AiJobWorker} 里会出现手写字符串，与异常类里的码各写一份、迟早漂移。
     */
    public static final String INVALID_REQUEST_CODE = "AI_INVALID_REQUEST";

    private AiException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }

    /** 503：Provider 不可达或超时（云端网络不通、自建网关没起）。 */
    public static final class ProviderUnavailable extends AiException {
        public ProviderUnavailable(String message) {
            super("AI_PROVIDER_UNAVAILABLE", 503, message);
        }
    }

    /** 502：Provider 返回非 2xx（密钥无效、模型不存在、被限流）。 */
    public static final class ProviderRejected extends AiException {
        public ProviderRejected(String message) {
            super("AI_PROVIDER_REJECTED", 502, message);
        }
    }

    /** 502：模型输出不是约定结构（提示注入成功或模型能力不足），不能当成功结果使用。 */
    public static final class InvalidModelOutput extends AiException {
        public InvalidModelOutput(String message) {
            super("AI_INVALID_MODEL_OUTPUT", 502, message);
        }
    }

    /** 404：作业不存在。 */
    public static final class JobNotFound extends AiException {
        public JobNotFound() {
            super("AI_JOB_NOT_FOUND", 404, "AI 作业不存在");
        }
    }

    /** 404：finding 不存在。 */
    public static final class FindingNotFound extends AiException {
        public FindingNotFound() {
            super("AI_FINDING_NOT_FOUND", 404, "AI 结论不存在");
        }
    }

    /** 404：目标资源不存在，或不在调用者的资源范围内。 */
    public static final class ResourceNotFound extends AiException {
        public ResourceNotFound() {
            // 两种情形共用同一个码是刻意的：区分它们等于告诉调用方"这个考试存在但不归你"（BE-01 要求隐藏存在性）
            super("AI_RESOURCE_NOT_FOUND", 404, "资源不存在");
        }
    }

    /** 429：当日作业配额已用尽（ai.limits.max-jobs-per-day）。 */
    public static final class QuotaExceeded extends AiException {
        public QuotaExceeded(int maxJobsPerDay) {
            super("AI_QUOTA_EXCEEDED", 429, "今日 AI 作业额度已用尽（上限 " + maxJobsPerDay + "）");
        }
    }

    /** 400：请求不合法（未知作业类型、缺少必要引用、输入超长）。 */
    public static final class InvalidRequest extends AiException {
        public InvalidRequest(String message) {
            super(INVALID_REQUEST_CODE, 400, message);
        }
    }

    /** 409：需要向量检索但 embedding 未启用（ai.limits.embedding-enabled=false）。 */
    public static final class EmbeddingDisabled extends AiException {
        public EmbeddingDisabled() {
            super("AI_EMBEDDING_DISABLED", 409, "向量检索未启用：语义查重当前使用文本相似度通道");
        }
    }
}
