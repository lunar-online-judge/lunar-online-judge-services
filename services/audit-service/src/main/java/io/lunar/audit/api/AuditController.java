package io.lunar.audit.api;

import io.lunar.audit.api.dto.RecordRequest;
import io.lunar.audit.domain.AuditService;
import io.lunar.audit.domain.dto.AuditPage;
import io.lunar.audit.domain.dto.AuditRecordView;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.security.Role;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计入口：
 * - 写入（服务间）：`POST /api/v1/internal/audit/records`——各服务转发操作者令牌调用（尽力而为，不阻断主流程）；
 * - 查询（管理端）：`GET /api/v1/audit/records`——老师/管理员；账本无更新/删除接口（追加式）。
 */
@RestController
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @PostMapping("/api/v1/internal/audit/records")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AuditRecordView>> record(
            @Valid @RequestBody RecordRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        String actorRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.replace(Role.AUTHORITY_PREFIX, ""))
                .findFirst().orElse(null);
        // SERVICE 令牌的 sub 是服务名（不是用户 uuid）：落到 service_id，actor_id 留空（BE-07）
        boolean serviceCaller = Role.SERVICE.name().equals(actorRole);
        AuditRecordView view = auditService.record(request.action(),
                serviceCaller ? null : authentication.getName(), actorRole,
                serviceCaller ? authentication.getName() : null,
                request.targetType(), request.targetId(), request.contestId(), request.detail(), requestId);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /**
     * 管理端查询：**仅 ADMIN**。BE-01 要求审计列表也按资源范围过滤，而账本只知道 contestId、
     * 不知道竞赛归属；在跨服务的范围查询落地前，先收紧为管理员专用（BACKEND_IMPROVEMENT_PLAN 的兼容说明：
     * "前端在完成测试并更新角色矩阵前继续 ADMIN-only"）。
     */
    @GetMapping("/api/v1/audit/records")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AuditPage>> query(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String contestId,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                auditService.query(action, actorId, contestId, page, pageSize)));
    }
}
