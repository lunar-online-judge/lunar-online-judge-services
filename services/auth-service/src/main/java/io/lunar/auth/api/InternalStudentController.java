package io.lunar.auth.api;

import io.lunar.auth.domain.StudentAccountService;
import io.lunar.auth.domain.UserException;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 服务间接口：学生账号由 contest-service 编排、auth-service 执行（docs/api.md"老师管理竞赛学生"）。
 * 鉴权过渡方案：要求操作者（老师/管理员）的有效令牌，由 contest 转发；
 * 服务间独立身份（客户端凭证）后续接入时替换。审计事件见 StudentAccountService 日志。
 */
@RestController
@RequestMapping("/api/v1/internal/students")
public class InternalStudentController {

    private final StudentAccountService studentAccountService;

    public InternalStudentController(StudentAccountService studentAccountService) {
        this.studentAccountService = studentAccountService;
    }

    /** 按学号或账户名查学生（两参数至少一个）；不存在返回 404。供 contest 判断"账号已存在则复用"。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> find(
            @RequestParam(required = false) String studentNo,
            @RequestParam(required = false) String username,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        StudentAccountService.CreatedStudent found = studentAccountService.findStudent(studentNo, username);
        if (found == null) {
            throw new UserException.NotFound();
        }
        return ResponseEntity.ok(ApiResponse.success(requestId, Map.of(
                "studentId", found.studentId(),
                "studentNo", found.studentNo(),
                "username", found.username(),
                "displayName", found.displayName(),
                "passwordChangeRequired", found.passwordChangeRequired())));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@Valid @RequestBody CreateStudentRequest request,
                                                                   Authentication authentication,
                                                                   @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        StudentAccountService.CreatedStudent created = studentAccountService.createStudent(
                request.studentNo(), request.username(), request.displayName(),
                request.initialPassword(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, Map.of(
                "studentId", created.studentId(),
                "studentNo", created.studentNo(),
                "username", created.username(),
                "displayName", created.displayName(),
                "passwordChangeRequired", created.passwordChangeRequired())));
    }

    @PostMapping("/{studentId}/password-reset")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetPassword(@PathVariable String studentId,
                                                                          @Valid @RequestBody ResetPasswordRequest request,
                                                                          Authentication authentication,
                                                                          @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        studentAccountService.resetStudentPassword(studentId, request.temporaryPassword(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, Map.of("passwordChangeRequired", true)));
    }

    public record CreateStudentRequest(@NotBlank String studentNo, @NotBlank String username,
                                       @NotBlank String displayName, @NotBlank String initialPassword) {}

    public record ResetPasswordRequest(@NotBlank String temporaryPassword) {}

    @RestControllerAdvice
    public static class UserExceptionMapper {
        @ExceptionHandler(UserException.class)
        public ResponseEntity<ApiResponse<Void>> handle(UserException e) {
            return ResponseEntity.status(e.httpStatus())
                    .body(ApiResponse.failure(currentRequestId(), e.code(), e.getMessage()));
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.failure(currentRequestId(), "VALIDATION_FAILED", "参数校验失败"));
        }

        private static String currentRequestId() {
            if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                    instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
                return attributes.getRequest().getHeader(RequestIdFilter.HEADER);
            }
            return null;
        }
    }
}
