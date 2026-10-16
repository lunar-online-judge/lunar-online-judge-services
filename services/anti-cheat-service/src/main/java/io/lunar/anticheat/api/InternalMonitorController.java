package io.lunar.anticheat.api;

import io.lunar.anticheat.domain.RiskEventService;
import io.lunar.anticheat.domain.dto.ReviewPage;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.web.RequestIdFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间风险事件查询（供 ai-service 的风险辅助研判取证据）。
 *
 * 只认服务身份 `roles=["SERVICE"]`，不经网关（gateway 无 `/api/v1/internal/**` 路由）：
 * 风险事件含学生行为细节，不能让"内网 + 任意已认证令牌"取走。
 *
 * 刻意复用面向监考视图的 {@link RiskEventService#list} 与 {@code ReviewPage} 而不是另造一套汇总 DTO：
 * 真正需要"按类型/等级聚合计数"的是消费方（AI 与后续分析读模型），
 * 在服务端预聚合只会多一个需要同步维护的契约。**上限由服务端夹取**（分页 pageSize 上限），
 * 调用方无法把这个接口变成全量导出。
 *
 * 与 {@code /api/v1/monitor/events} 的区别只有一个：那个是 ADMIN 专用（BE-01 未落跨服务范围查询前的收紧），
 * 这个只给服务身份——AI 作业由后台 worker 执行，没有用户上下文，因此不能走前者。
 */
@RestController
@RequestMapping("/api/v1/internal/monitor")
public class InternalMonitorController {

    private final RiskEventService riskEventService;

    public InternalMonitorController(RiskEventService riskEventService) {
        this.riskEventService = riskEventService;
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<ApiResponse<ReviewPage>> list(
            @RequestParam String contestId,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                riskEventService.list(contestId, studentId, severity, reviewStatus, page, pageSize)));
    }
}
