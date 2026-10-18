package io.lunar.auth.domain;

import io.lunar.auth.api.dto.CreateUserRequest;
import io.lunar.auth.api.dto.UserPage;
import io.lunar.auth.api.dto.UserView;
import io.lunar.auth.infra.UserEntity;
import io.lunar.auth.infra.UserMapper;
import io.lunar.common.audit.AuditRecorder;
import io.lunar.common.security.Role;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理端账号管理（管理员专用）：创建管理员/教师/学生、分页查询、重置密码、启用禁用。
 *
 * <p>安全边界：明文密码只存在于当前请求生命周期，落库前立即 BCrypt 哈希，响应与审计都不回显；
 * 重置密码或禁用账号时写 {@code tokens_valid_after}，让已签发的离线 JWT 立即失效（BE-09）。</p>
 *
 * <p>角色映射：平台现有角色只有 ADMIN / TEACHER / CANDIDATE，"子管理员"落到 ADMIN；
 * 不新增角色是因为授权规则、菜单与各服务的 {@code @PreAuthorize} 都按这三个角色编写。</p>
 */
@Component
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final long MAX_PAGE_SIZE = 100;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecorder auditRecorder;

    public UserAdminService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                            AuditRecorder auditRecorder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditRecorder = auditRecorder;
    }

    /** 分页查询：角色/状态/班级精确过滤，keyword 模糊匹配账号、姓名与学号。 */
    public UserPage list(String role, String status, String keyword, String className,
                         long page, long pageSize) {
        long normalizedPage = Math.max(1, page);
        long normalizedPageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        long total = userMapper.selectCount(filterQuery(role, status, keyword, className));

        long offset = (normalizedPage - 1) * normalizedPageSize;
        List<UserEntity> users = userMapper.selectList(
                filterQuery(role, status, keyword, className)
                        .orderByDesc(UserEntity::getCreatedAt)
                        .last("limit " + normalizedPageSize + " offset " + offset));
        List<UserView> items = users.stream().map(UserView::of).toList();
        return new UserPage(items, total, normalizedPage, normalizedPageSize);
    }

    /** 学生班级列表：去重、按名称排序，供教师筛选与按班操作。 */
    public List<String> listClassNames() {
        return userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                        .eq(UserEntity::getRole, Role.CANDIDATE.name())
                        .isNotNull(UserEntity::getClassName)
                        .ne(UserEntity::getClassName, "")
                        .select(UserEntity::getClassName)
                        .groupBy(UserEntity::getClassName)
                        .orderByAsc(UserEntity::getClassName))
                .stream().map(UserEntity::getClassName).toList();
    }

    /** 创建账号：账号名全局唯一；学生额外要求学号唯一。 */
    public UserView create(CreateUserRequest request, String operatorId, String operatorToken) {
        Role role = parseAssignableRole(request.role());
        String username = request.username().trim();
        String displayName = request.displayName().trim();
        requirePassword(request.initialPassword());

        if (userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username)) > 0) {
            throw new UserException.UsernameExists();
        }

        String studentNo = trimToNull(request.studentNo());
        String className = trimToNull(request.className());
        if (role == Role.CANDIDATE) {
            if (studentNo == null) {
                throw new UserException.ValidationFailed("学生账号必须填写学号");
            }
            if (className == null) {
                throw new UserException.ValidationFailed("学生账号必须填写班级");
            }
            if (userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getStudentNo, studentNo)) > 0) {
                throw new UserException.StudentNoExists();
            }
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.initialPassword()));
        user.setDisplayName(displayName);
        user.setRole(role.name());
        user.setStatus(STATUS_ACTIVE);
        user.setStudentNo(role == Role.CANDIDATE ? studentNo : null);
        user.setClassName(role == Role.CANDIDATE ? className : null);
        user.setManagedByTeacherId(operatorId);
        user.setPasswordChangeRequired(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userMapper.insert(user);

        log.info("AUDIT event=USER_CREATED userId={} role={} studentNo={}",
                user.getId(), role.name(), user.getStudentNo());
        auditRecorder.record("USER_CREATED", "USER_ACCOUNT", user.getId(), null,
                "创建账号 " + username + "（" + role.name() + "）", null, operatorToken);
        return UserView.of(user);
    }

    /** 重置密码：任意角色都可重置；新密码只回传成功标记，不返回任何密码材料。 */
    public void resetPassword(String userId, String temporaryPassword, String operatorToken) {
        requirePassword(temporaryPassword);

        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new UserException.NotFound();
        }
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setTokensValidAfter(Instant.now());
        user.setPasswordChangeRequired(true);
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        log.info("AUDIT event=USER_PASSWORD_RESET userId={}", userId);
        auditRecorder.record("USER_PASSWORD_RESET", "USER_ACCOUNT", userId, null,
                "管理员重置密码", null, operatorToken);
    }

    /** 启用/禁用账号：禁用会立即吊销已签发令牌，且不允许禁用当前登录账号。 */
    public UserView updateStatus(String userId, String status, String operatorId, String operatorToken) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new UserException.ValidationFailed("状态只能是 ACTIVE 或 DISABLED");
        }
        if (userId.equals(operatorId) && STATUS_DISABLED.equals(status)) {
            throw new UserException.ValidationFailed("不能禁用当前登录账号");
        }

        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new UserException.NotFound();
        }
        user.setStatus(status);
        if (STATUS_DISABLED.equals(status)) {
            user.setTokensValidAfter(Instant.now());
        }
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        log.info("AUDIT event=USER_STATUS_CHANGED userId={} status={}", userId, status);
        auditRecorder.record("USER_STATUS_CHANGED", "USER_ACCOUNT", userId, null,
                "账号状态改为 " + status, null, operatorToken);
        return UserView.of(user);
    }

    private LambdaQueryWrapper<UserEntity> filterQuery(String role, String status, String keyword,
                                                       String className) {
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(role)) {
            query.eq(UserEntity::getRole, role.trim());
        }
        if (StringUtils.hasText(status)) {
            query.eq(UserEntity::getStatus, status.trim());
        }
        if (StringUtils.hasText(className)) {
            query.eq(UserEntity::getClassName, className.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String trimmedKeyword = keyword.trim();
            query.and(nested -> nested
                    .like(UserEntity::getUsername, trimmedKeyword)
                    .or().like(UserEntity::getDisplayName, trimmedKeyword)
                    .or().like(UserEntity::getStudentNo, trimmedKeyword));
        }
        return query;
    }

    private static Role parseAssignableRole(String roleName) {
        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (IllegalArgumentException exception) {
            throw new UserException.ValidationFailed("角色只能是 ADMIN、TEACHER 或 CANDIDATE");
        }
        if (role != Role.ADMIN && role != Role.TEACHER && role != Role.CANDIDATE) {
            throw new UserException.ValidationFailed("角色只能是 ADMIN、TEACHER 或 CANDIDATE");
        }
        return role;
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new UserException.WeakPassword();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
