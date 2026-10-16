package io.lunar.ai.infra;

import io.lunar.ai.infra.dto.ContestSnapshotView;
import io.lunar.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * contest-service 服务间接口：考试快照（范围判断用）。
 *
 * 用服务身份取"这场考试归哪位老师"，AI 侧才能自己做 BE-01 的资源范围判断——
 * 否则只能像 anti-cheat/audit 早期那样先把查询收紧为 ADMIN-only（那样老师就用不了 AI 能力）。
 */
@HttpExchange("/api/v1/internal/contests")
public interface ContestInternalApi {

    @GetExchange("/{contestId}/snapshot")
    ApiResponse<ContestSnapshotView> snapshot(@PathVariable("contestId") String contestId,
                                             @RequestHeader("Authorization") String authorization);
}
