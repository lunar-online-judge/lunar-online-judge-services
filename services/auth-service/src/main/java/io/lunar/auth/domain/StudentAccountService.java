package io.lunar.auth.domain;

import io.lunar.auth.domain.dto.CreatedStudent;
import io.lunar.auth.infra.UserEntity;
import io.lunar.auth.infra.UserMapper;
import io.lunar.common.audit.AuditRecorder;
import io.lunar.common.security.Role;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 学生账号管理（服务间接口，供 contest-service 编排调用，docs/architecture.md §2.1.1）：
 * 明文密码只存在于当前请求生命周期，服务端立即哈希；响应不含密码或哈希。
 * 幂等由 username 全局唯一 + studentNo 组织内唯一约束兜底，重复创建返回 409。
 * 审计：账号创建/密码重置写 audit-service 账本（尽力而为，失败不阻断），结构化日志同时保留兜底；
 * 账本内容不含密码或哈希。
 */
@Component
public class StudentAccountService {

    private static final Logger log = LoggerFactory.getLogger(StudentAccountService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecorder auditRecorder;

    public StudentAccountService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                                 AuditRecorder auditRecorder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditRecorder = auditRecorder;
    }

    /** 按学号或账户名查学生账号；两个条件至少传一个，都不匹配返回 null。 */
    public CreatedStudent findStudent(String studentNo, String username) {
        if ((studentNo == null || studentNo.isBlank()) && (username == null || username.isBlank())) {
            throw new UserException.NotFound();
        }
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>();
        if (studentNo != null && !studentNo.isBlank()) {
            query.eq(UserEntity::getStudentNo, studentNo);
        }
        if (username != null && !username.isBlank()) {
            query.eq(UserEntity::getUsername, username);
        }
        UserEntity user = userMapper.selectOne(query.last("limit 1"));
        if (user == null || !Role.CANDIDATE.name().equals(user.getRole())) {
            return null;
        }
        return new CreatedStudent(user.getId(), user.getStudentNo(), user.getUsername(),
                user.getDisplayName(), user.getClassName(),
                Boolean.TRUE.equals(user.getPasswordChangeRequired()));
    }

    /** 按用户 ID 查学生账号；非学生或不存在返回 null。 */
    public CreatedStudent findStudentById(String studentId) {
        UserEntity user = userMapper.selectById(studentId);
        if (user == null || !Role.CANDIDATE.name().equals(user.getRole())) {
            return null;
        }
        return new CreatedStudent(user.getId(), user.getStudentNo(), user.getUsername(),
                user.getDisplayName(), user.getClassName(),
                Boolean.TRUE.equals(user.getPasswordChangeRequired()));
    }

    public CreatedStudent createStudent(String studentNo, String username, String displayName,
                                        String className, String initialPassword,
                                        String operatorTeacherId, String operatorToken) {
        if (initialPassword == null || initialPassword.length() < 8) {
            throw new UserException.WeakPassword();
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username)) > 0) {
            throw new UserException.UsernameExists();
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getStudentNo, studentNo)) > 0) {
            throw new UserException.StudentNoExists();
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setDisplayName(displayName);
        user.setRole(Role.CANDIDATE.name());
        user.setStatus("ACTIVE");
        user.setStudentNo(studentNo);
        user.setClassName(className);
        user.setManagedByTeacherId(operatorTeacherId);
        user.setPasswordChangeRequired(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userMapper.insert(user);

        log.info("AUDIT event=STUDENT_CREATED studentId={} studentNo={} managedBy={}",
                user.getId(), studentNo, operatorTeacherId);
        auditRecorder.record("STUDENT_CREATED", "STUDENT_ACCOUNT", user.getId(), null,
                "学号 " + studentNo, null, operatorToken);
        return new CreatedStudent(user.getId(), studentNo, username, displayName, className, true);
    }

    /**
     * 重置学生密码：新临时密码立即哈希，置 passwordChangeRequired；不返回任何密码材料。
     * BE-01：只允许重置 CANDIDATE 账号（目标非学生按不存在处理，避免探测账号）；
     * **重置不改变账号归属**——`managedByTeacherId` 表达管理关系，不是"最近一次操作人"。
     */
    public void resetStudentPassword(String studentId, String temporaryPassword, String operatorToken) {
        if (temporaryPassword == null || temporaryPassword.length() < 8) {
            throw new UserException.WeakPassword();
        }
        UserEntity user = userMapper.selectById(studentId);
        if (user == null || !Role.CANDIDATE.name().equals(user.getRole())) {
            throw new UserException.NotFound();
        }
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        // 敏感重置必须让已签发的令牌失效（BE-09）：JWT 离线校验，不标记的话旧令牌在到期前照样能用
        user.setTokensValidAfter(Instant.now());
        user.setPasswordChangeRequired(true);
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        log.info("AUDIT event=STUDENT_PASSWORD_RESET studentId={}", studentId);
        auditRecorder.record("STUDENT_PASSWORD_RESET", "STUDENT_ACCOUNT", studentId, null,
                "重置为临时密码", null, operatorToken);
    }
}
