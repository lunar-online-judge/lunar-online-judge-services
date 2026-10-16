package io.lunar.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 请求追踪：X-Request-Id 透传（缺失时生成），写入响应头并包装请求——
 * 控制器经 @RequestHeader(HEADER) 恒可读到解析后的 ID。
 * 由 WebCommonAutoConfiguration 注册，服务无需自行声明。
 *
 * 请求处理期间同时写入 {@link #MDC_KEY}：没有控制器参数可用时（如审计留痕的尽力而为调用）
 * 也能拿到同一条追踪 ID，避免"调用方传 null、链路断开"。
 */
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";

    /** MDC 键：请求处理线程内可取到本次请求的追踪 ID。 */
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incomingRequestId = request.getHeader(HEADER);
        String requestId = incomingRequestId == null || incomingRequestId.isBlank()
                ? UUID.randomUUID().toString() : incomingRequestId;
        response.setHeader(HEADER, requestId);
        HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return HEADER.equals(name) ? requestId : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return HEADER.equals(name)
                        ? Collections.enumeration(List.of(requestId))
                        : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> headerNames = new LinkedHashSet<>();
                Enumeration<String> originalNames = super.getHeaderNames();
                while (originalNames.hasMoreElements()) {
                    headerNames.add(originalNames.nextElement());
                }
                headerNames.add(HEADER);
                return Collections.enumeration(headerNames);
            }
        };
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(wrappedRequest, response);
        } finally {
            // 线程池复用：必须清理，否则下个请求会串上本次的追踪 ID
            MDC.remove(MDC_KEY);
        }
    }
}
