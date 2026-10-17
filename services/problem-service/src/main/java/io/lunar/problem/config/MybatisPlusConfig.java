package io.lunar.problem.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件（第二批正确性项）。
 *
 * 缺了它，`selectPage` 生成的 SQL **没有 LIMIT**、`total` 恒为 0——分页与总数全错；
 * 更糟的是 `pageSize` 传负数时插件不会加 LIMIT，等于把列表接口变成全表导出通道。
 *
 * 因此这里同时设一个硬上限：任何列表接口单页最多 100 行（与 analytics/ai 的口径一致）。
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页最大行数：超过即按上限截断，避免列表接口被当成全量导出。 */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
