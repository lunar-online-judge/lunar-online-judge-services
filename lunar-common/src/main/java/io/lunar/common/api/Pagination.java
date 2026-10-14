package io.lunar.common.api;

/**
 * 列表接口分页约定（docs/api.md"分页"）：page 从 1 起，默认每页 20 条。
 * 注解属性要求编译期常量，故以字符串常量集中在此，避免各服务各写一份。
 */
public final class Pagination {

    /** 默认页码（首页）。 */
    public static final String DEFAULT_PAGE = "1";

    /** 默认每页条数。 */
    public static final String DEFAULT_PAGE_SIZE = "20";

    private Pagination() {
    }
}
