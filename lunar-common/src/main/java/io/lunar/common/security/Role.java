package io.lunar.common.security;

public enum Role {
    ADMIN, CANDIDATE, INVIGILATOR, SERVICE, TEACHER;

    /** Spring Security 的角色权限前缀：roles claim 的 "ADMIN" → authority "ROLE_ADMIN"。 */
    public static final String AUTHORITY_PREFIX = "ROLE_";
}
