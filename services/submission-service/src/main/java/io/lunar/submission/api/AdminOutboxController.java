package io.lunar.submission.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.submission.domain.OutboxAdminService;
import io.lunar.submission.infra.OutboxEventEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 死信运维接口（BACKEND_IMPROVEMENT_PLAN BE-07）：仅 ADMIN。
 *
 * 路径放在 submission 已注册的前缀下（网关路由 /api/v1/submissions/**），避免为了一个运维接口新增路由；
 * 权限由 {@code hasRole('ADMIN')} 保证，考生令牌拿不到。
 */
@RestController
@RequestMapping("/api/v1/submissions/admin/dead-letters")
public class AdminOutboxController {

    private final OutboxAdminService outboxAdminService;

    public AdminOutboxController(OutboxAdminService outboxAdminService) {
        this.outboxAdminService = outboxAdminService;
    }

    /** 列出投不出去（已转死信）的事件。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<OutboxEventEntity>>> list(
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, outboxAdminService.listDeadLetters()));
    }

    /** 人工重放一条死信：清空失败计数与退避，让发布器重新投递。 */
    @PostMapping("/{eventId}/replay")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> replay(
            @PathVariable String eventId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        outboxAdminService.replay(eventId);
        return ResponseEntity.ok(ApiResponse.success(requestId, null));
    }
}
