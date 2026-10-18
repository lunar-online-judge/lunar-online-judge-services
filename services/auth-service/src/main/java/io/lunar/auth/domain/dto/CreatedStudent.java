package io.lunar.auth.domain.dto;

/** 学生账号视图：字段与 contest-service 的 AuthInternalClient 取值约定一一对应，不含密码材料。 */
public record CreatedStudent(String studentId, String studentNo, String username,
                             String displayName, String className,
                             boolean passwordChangeRequired) {}
