package io.lunar.analytics.domain;

/**
 * 分析接口的分页入参（已归一化）。
 *
 * 为什么要有这样一个类型：每页上限必须与 MybatisPlusConfig 的 {@code setMaxLimit(100)} 一致——
 * 那是分页插件在 SQL 层强制的上限，如果接口层允许更大的 pageSize，调用方会拿到"请求 500 条、
 * 实际 100 条但没有 total 之外的任何提示"这种难查的行为。把归一化收在一处，两个数字只会一起改。
 */
public record AnalyticsPageRequest(long page, long pageSize) {

    /** 单页最大条数：与分页插件的 maxLimit 对齐。 */
    public static final long MAX_PAGE_SIZE = 100L;

    /** 默认每页条数：与 docs/api.md"分页"的约定一致（20）。 */
    public static final long DEFAULT_PAGE_SIZE = 20L;

    /**
     * 归一化：页码小于 1 归为首页（而不是报错——"第 0 页"在调用方看来就是首页），
     * 每页条数越界则夹取到 [1, MAX_PAGE_SIZE]，避免调用方用一个超大值绕过上限或让 SQL 直接失败。
     */
    public static AnalyticsPageRequest of(long page, long pageSize) {
        long normalizedPage = page < 1 ? 1 : page;
        long normalizedPageSize = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return new AnalyticsPageRequest(normalizedPage, normalizedPageSize);
    }

    /** offset 分页的起点。深分页（大 offset）的性能边界见本轮报告，不在此处做游标分页。 */
    public long offset() {
        return (page - 1) * pageSize;
    }
}
