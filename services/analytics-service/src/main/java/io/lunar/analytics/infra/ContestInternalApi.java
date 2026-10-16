package io.lunar.analytics.infra;

import io.lunar.analytics.infra.dto.ContestSnapshotView;
import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * contest-service 服务间接口：考试快照（分析侧参照数据的唯一来源）。
 *
 * 为什么分析读模型必须走这个接口而不是查库：仓库明令服务禁止跨 schema 查库（docs/services.md），
 * 而"竞赛名/归属老师/名单/锁定题目版本"都住在 contest_schema 里。
 */
@HttpExchange("/api/v1/internal/contests")
public interface ContestInternalApi {

    @GetExchange("/{contestId}/snapshot")
    ApiResponse<ContestSnapshotView> snapshot(@PathVariable("contestId") String contestId,
                                             @RequestHeader("Authorization") String authorization);
}
