package io.lunar.common.web;

import io.lunar.common.api.ApiExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * lunar-common Web 组件自动配置：
 * 以最高优先级注册 RequestIdFilter，保证它在 Spring Security 过滤链之前运行（401 响应也携带追踪头）；
 * 同时注册全局异常处理器 ApiExceptionHandler，各服务无需再自行声明错误响应格式。
 */
@AutoConfiguration
public class WebCommonAutoConfiguration {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }
}
