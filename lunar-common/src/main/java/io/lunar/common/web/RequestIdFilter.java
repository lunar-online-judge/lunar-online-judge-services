package io.lunar.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求追踪：X-Request-Id 透传（缺失时生成），写入响应头并包装请求——
 * 控制器经 @RequestHeader(HEADER) 恒可读到解析后的 ID。
 * 由 WebCommonAutoConfiguration 注册，服务无需自行声明。
 */
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        final String requestId = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
        response.setHeader(HEADER, requestId);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return HEADER.equals(name) ? requestId : super.getHeader(name);
            }

            @Override
            public java.util.Enumeration<String> getHeaders(String name) {
                return HEADER.equals(name)
                        ? java.util.Collections.enumeration(java.util.List.of(requestId))
                        : super.getHeaders(name);
            }

            @Override
            public java.util.Enumeration<String> getHeaderNames() {
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                java.util.Enumeration<String> original = super.getHeaderNames();
                while (original.hasMoreElements()) names.add(original.nextElement());
                names.add(HEADER);
                return java.util.Collections.enumeration(names);
            }
        }, response);
    }
}
