package io.lunar.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前请求追踪 ID 的读取口：{@link RequestIdFilter} 已保证 X-Request-Id 存在并写入请求头，
 * 这里供响应组装（异常处理）时取回。无 Web 上下文（异步线程、单元测试）时返回 null。
 */
public final class RequestIds {

    private RequestIds() {
    }

    public static String currentRequestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getHeader(RequestIdFilter.HEADER);
        }
        return null;
    }
}
