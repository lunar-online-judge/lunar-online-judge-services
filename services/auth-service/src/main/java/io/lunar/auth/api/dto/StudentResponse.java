package io.lunar.auth.api.dto;

import io.lunar.auth.domain.StudentAccountService;

/** 学生账号视图：字段与 contest-service 的 AuthInternalClient 取值约定一一对应。 */
public record StudentResponse(String studentId, String studentNo, String username,
                              String displayName, boolean passwordChangeRequired) {

    public static StudentResponse of(StudentAccountService.CreatedStudent student) {
        return new StudentResponse(student.studentId(), student.studentNo(), student.username(),
                student.displayName(), student.passwordChangeRequired());
    }
}
