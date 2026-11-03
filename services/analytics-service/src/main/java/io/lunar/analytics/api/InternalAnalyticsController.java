package io.lunar.analytics.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import io.lunar.analytics.api.dto.RebuildRequest;
import io.lunar.analytics.domain.ContestRefRefreshService;
import io.lunar.analytics.domain.Identifiers;
import io.lunar.analytics.domain.dto.RebuildResult;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间接口：显式重建成绩读模型。
 *
 * 授权是 {@code SERVICE} **或** ADMIN：这个端点有两种真实用法——运维排障时用管理员令牌手工触发，
 * 以及未来的结算任务用服务身份触发。只认 SERVICE 会让前者用不了（管理员拿不到服务令牌），
 * 只认 ADMIN 则会把自动化挡在门外。
 *
 * 不经网关（gateway 刻意不配 {@code /api/v1/internal/**}），只能内网直连。
 */
@RestController
@RequestMapping("/api/v1/internal/analytics")
public class InternalAnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(InternalAnalyticsController.class);

    private final ContestRefRefreshService contestRefRefreshService;

    public InternalAnalyticsController(ContestRefRefreshService contestRefRefreshService) {
        this.contestRefRefreshService = contestRefRefreshService;
    }

    /**
     * 刷新指定考试的**参照数据**（学号/姓名、题目清单、在册考生）。
     *
     * 简化说明：成绩与名次已改为**实时聚合**（见 ScoreboardService），不再有"重算成绩"这件事——
     * 所以这个端点现在只负责把 contest 的快照拉进来。入参 `studentCount` 恒为 0，保留字段是为了
     * 不破坏既有调用方的响应契约。
     */
    @PostMapping("/rebuild")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    public ResponseEntity<ApiResponse<RebuildResult>> refreshReferences(
            @Valid @RequestBody RebuildRequest request,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        String contestId = Identifiers.requireUuid(request.contestId());
        boolean refreshed = contestRefRefreshService.refresh(contestId);
        log.info("参照数据刷新完成：contestId={} refreshed={}", contestId, refreshed);
        return ResponseEntity.ok(ApiResponse.success(requestId,
                new RebuildResult(contestId, 0, Instant.now())));
    }
}
