package io.lunar.auth.api;

import io.lunar.auth.api.dto.CreateUserRequest;
import io.lunar.auth.api.dto.ResetPasswordRequest;
import io.lunar.auth.api.dto.UpdateUserStatusRequest;
import io.lunar.auth.api.dto.UserPage;
import io.lunar.auth.api.dto.UserView;
import io.lunar.auth.domain.UserAdminService;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端账号管理入口（仅 ADMIN）。
 *
 * <p>提供管理员创建子管理员/教师/学生、分页查询、重置密码与启用禁用。
 * 与服务间的 {@code /api/v1/internal/students/**} 分开：内部接口只服务 contest 编排，
 * 这里面向管理端页面，鉴权与资源边界不同，不能合并。</p>
 */
@RestController
@RequestMapping("/api/v1/auth/users")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    /** 账号分页：按角色/状态精确过滤，keyword 模糊匹配账号、姓名或学号。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserPage>> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                userAdminService.list(role, status, keyword, page, pageSize)));
    }

    /** 创建账号：role 取 ADMIN（子管理员）/ TEACHER / CANDIDATE。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserView>> create(
            @Valid @RequestBody CreateUserRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserView view = userAdminService.create(request, authentication.getName(), authorization);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 重置任意账号密码：不返回密码，只置 passwordChangeRequired。 */
    @PostMapping("/{userId}/password-reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable String userId,
            @Valid @RequestBody ResetPasswordRequest request,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        userAdminService.resetPassword(userId, request.temporaryPassword(), authorization);
        return ResponseEntity.ok(ApiResponse.success(requestId, null));
    }

    /** 启用/禁用账号：禁用同时吊销该账号已签发的令牌。 */
    @PostMapping("/{userId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserView>> updateStatus(
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserStatusRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserView view = userAdminService.updateStatus(userId, request.status(),
                authentication.getName(), authorization);
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }
}
