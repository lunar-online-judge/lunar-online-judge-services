package io.lunar.analytics.api;

import io.lunar.analytics.api.dto.RebuildRequest;
import io.lunar.analytics.domain.AnalyticsRebuildService;
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

    private final AnalyticsRebuildService analyticsRebuildService;

    public InternalAnalyticsController(AnalyticsRebuildService analyticsRebuildService) {
        this.analyticsRebuildService = analyticsRebuildService;
    }

    /**
     * 重算指定考试的成绩与排名。同一考试正在重算时返回 409（{@code ANALYTICS_REBUILD_IN_PROGRESS}），
     * 而不是排队——调用方需要知道"你要的事已经在做"，否则会不停重试并叠加负载。
     */
    @PostMapping("/rebuild")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    public ResponseEntity<ApiResponse<RebuildResult>> rebuild(
            @Valid @RequestBody RebuildRequest request,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        String contestId = Identifiers.requireUuid(request.contestId());
        return ResponseEntity.ok(ApiResponse.success(requestId, analyticsRebuildService.rebuild(contestId)));
    }
}
