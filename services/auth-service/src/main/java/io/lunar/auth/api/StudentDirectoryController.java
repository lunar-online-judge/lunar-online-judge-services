package io.lunar.auth.api;

import io.lunar.auth.api.dto.UserPage;
import io.lunar.auth.domain.UserAdminService;
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

import java.util.List;

/**
 * 共享学生目录：教师/管理员按班级、关键词读取启用的学生账号，
 * 供考试名单从既有学生中选择，而不是临时创建账号。
 */
@RestController
@RequestMapping("/api/v1/auth/students")
public class StudentDirectoryController {

    private static final String ROLE_CANDIDATE = "CANDIDATE";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final UserAdminService userAdminService;

    public StudentDirectoryController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<UserPage>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String className,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                userAdminService.list(ROLE_CANDIDATE, STATUS_ACTIVE, keyword, className, page, pageSize)));
    }

    @GetMapping("/classes")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<String>>> classes(
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, userAdminService.listClassNames()));
    }
}
