package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.domain.ContestSnapshotService;
import io.lunar.contest.domain.dto.ContestSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间接口：考试快照（供 analytics-service 拉取参照数据）。
 *
 * 只认**服务身份**（{@code roles=["SERVICE"]}）：快照含全员名单与题目版本清单，
 * 不能因为"内网 + 任意已认证令牌"就被取走（与 problem 测试点接口同一收紧原则，BE-01）。
 * 不经网关——gateway 刻意不配置 {@code /api/v1/internal/**} 路由。
 */
@RestController
@RequestMapping("/api/v1/internal/contests/{contestId}/snapshot")
public class InternalContestSnapshotController {

    private final ContestSnapshotService contestSnapshotService;

    public InternalContestSnapshotController(ContestSnapshotService contestSnapshotService) {
        this.contestSnapshotService = contestSnapshotService;
    }

    @GetMapping
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<ApiResponse<ContestSnapshot>> snapshot(
            @PathVariable String contestId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, contestSnapshotService.snapshot(contestId)));
    }
}
