package io.lunar.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 追踪 ID 契约：透传或生成、回写响应头、请求线程内可从 MDC 取到、请求结束必须清理。 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void incomingRequestIdIsEchoedAndExposedThroughMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "trace-from-caller");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (wrappedRequest, wrappedResponse) -> requestIdDuringChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertEquals("trace-from-caller", requestIdDuringChain.get());
        assertEquals("trace-from-caller", response.getHeader(RequestIdFilter.HEADER));
        // 线程池复用：请求结束必须清理，否则下个请求会串上本次的 ID
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void missingRequestIdIsGeneratedAndConsistentBetweenHeaderAndMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (wrappedRequest, wrappedResponse) -> requestIdDuringChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertNotNull(requestIdDuringChain.get());
        assertEquals(requestIdDuringChain.get(), response.getHeader(RequestIdFilter.HEADER));
    }
}
