package io.lunar.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
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
 */
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incomingRequestId = request.getHeader(HEADER);
        String requestId = incomingRequestId == null || incomingRequestId.isBlank()
                ? UUID.randomUUID().toString() : incomingRequestId;
        response.setHeader(HEADER, requestId);
        chain.doFilter(new HttpServletRequestWrapper(request) {
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
        }, response);
    }
}
