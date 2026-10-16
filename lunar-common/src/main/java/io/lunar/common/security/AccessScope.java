package io.lunar.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

/**
 * 调用者的资源授权范围（BACKEND_IMPROVEMENT_PLAN BE-01）。
 *
 * ADMIN 全局；TEACHER 仅限自己拥有的资源；其余角色没有管理范围。
 * 存在的意义是把"筛选条件"和"授权"分开：筛选来自用户请求，范围来自已验证令牌，
 * 查询时先叠加范围再套筛选，分页 total 自然落在范围内；越权按资源不存在处理（404 隐藏存在）。
 *
 * 身份只取 authorities 与 sub——**不取请求体、查询参数或任何代理头里的 actor 字段**。
 */
public record AccessScope(boolean global, String ownerId) {

    public static AccessScope of(Authentication authentication) {
        if (authentication == null) {
            return new AccessScope(false, null);
        }
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        boolean admin = authorities.contains(Role.AUTHORITY_PREFIX + Role.ADMIN.name());
        return new AccessScope(admin, authentication.getName());
    }

    /** 该资源是否在范围内：ADMIN 全通过；否则要求资源归属等于调用者（无归属的资源对老师不可见）。 */
    public boolean allows(String resourceOwnerId) {
        return global || (ownerId != null && ownerId.equals(resourceOwnerId));
    }
}
