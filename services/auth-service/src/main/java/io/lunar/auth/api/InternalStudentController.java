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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间接口：学生账号由 contest-service 编排、auth-service 执行（docs/api.md"老师管理竞赛学生"）。
 * 鉴权过渡方案：要求操作者（老师/管理员）的有效令牌，由 contest 转发；
 * 服务间独立身份（客户端凭证）后续接入时替换。审计事件见 StudentAccountService 日志。
 * 异常响应由 lunar-common 的全局异常处理器统一产出，本类不再自带 advice。
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
    public ResponseEntity<ApiResponse<StudentResponse>> find(
            @RequestParam(required = false) String studentNo,
            @RequestParam(required = false) String username,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        StudentAccountService.CreatedStudent found = studentAccountService.findStudent(studentNo, username);
        if (found == null) {
            throw new UserException.NotFound();
        }
        return ResponseEntity.ok(ApiResponse.success(requestId, StudentResponse.of(found)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<StudentResponse>> create(
            @Valid @RequestBody CreateStudentRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        StudentAccountService.CreatedStudent created = studentAccountService.createStudent(
                request.studentNo(), request.username(), request.displayName(),
                request.initialPassword(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, StudentResponse.of(created)));
    }

    @PostMapping("/{studentId}/password-reset")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<PasswordResetResponse>> resetPassword(
            @PathVariable String studentId,
            @Valid @RequestBody ResetPasswordRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        studentAccountService.resetStudentPassword(studentId, request.temporaryPassword(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, new PasswordResetResponse(true)));
    }

    public record CreateStudentRequest(@NotBlank String studentNo, @NotBlank String username,
                                       @NotBlank String displayName, @NotBlank String initialPassword) {}

    public record ResetPasswordRequest(@NotBlank String temporaryPassword) {}

    /** 学生账号视图：字段与 contest-service 的 AuthInternalClient 取值约定一一对应。 */
    public record StudentResponse(String studentId, String studentNo, String username,
                                  String displayName, boolean passwordChangeRequired) {
        static StudentResponse of(StudentAccountService.CreatedStudent student) {
            return new StudentResponse(student.studentId(), student.studentNo(), student.username(),
                    student.displayName(), student.passwordChangeRequired());
        }
    }

    public record PasswordResetResponse(boolean passwordChangeRequired) {}
}
