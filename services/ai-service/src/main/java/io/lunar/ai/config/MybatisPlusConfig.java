package io.lunar.ai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件：分页计数（作业与 finding 列表的 total 依赖它）。
 *
 * 显式设置 {@code maxLimit = 100}：默认不设上限时，调用方传一个巨大的 pageSize 就等于绕过
 * 分页把整张表拉走（仓库里已有三个服务的分页拦截器漏了这一项）。
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数上限：超过即被拦截器截断。 */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
