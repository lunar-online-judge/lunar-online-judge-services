package io.lunar.analytics.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件：分页（下一轮的聚合查询依赖 total）。
 *
 * {@code setMaxLimit(100)} 是刻意的上限：分析侧的事实表会随考试规模线性增长，
 * 未设上限的单页查询一次就能把整张表拉进内存，而聚合接口的调用方（管理端页面）并不需要那么大的页。
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数：超过由插件改写为这个值，而不是静默全表返回。 */
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
