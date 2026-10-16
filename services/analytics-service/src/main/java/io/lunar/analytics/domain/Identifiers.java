package io.lunar.analytics.domain;

import java.util.UUID;

/**
 * 路径/参数里的资源标识校验。
 *
 * 为什么把"不是合法 UUID"当作资源不存在（而不是参数校验失败）：分析接口的路径参数是 uuid，
 * 一个连格式都不对的 id 与"这个资源不存在"对调用方是同一件事；分成两个错误码只会多泄一点信息
 * （例如"这个字符串格式对但资源不存在"与"格式不对"的差别，可以用来试探 id 的形态）。
 */
public final class Identifiers {

    private Identifiers() {
    }

    /** 是否是合法的 UUID 文本；null/空白/超长一律不算。 */
    public static boolean isUuid(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(candidate);
            return true;
        } catch (IllegalArgumentException exception) {
            // UUID.fromString 用异常表达"格式不对"：这里它是可预期的输入问题，不是故障
            return false;
        }
    }

    /**
     * 校验并原样返回；不合法时抛 {@link AnalyticsException.ResourceNotFound}。
     *
     * 由调用方（ScopeGuard / 控制器）保证"接下来用的 id 一定合法"，服务层就不必再各写一遍防御。
     */
    public static String requireUuid(String candidate) {
        if (!isUuid(candidate)) {
            throw new AnalyticsException.ResourceNotFound();
        }
        return candidate;
    }
}
