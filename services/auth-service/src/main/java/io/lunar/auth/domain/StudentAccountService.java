package io.lunar.auth.domain;

import io.lunar.auth.infra.UserEntity;
import io.lunar.auth.infra.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 学生账号管理（服务间接口，供 contest-service 编排调用，docs/architecture.md §2.1.1）：
 * 明文密码只存在于当前请求生命周期，服务端立即哈希；响应不含密码或哈希。
 * 幂等由 username 全局唯一 + studentNo 组织内唯一约束兜底，重复创建返回 409。
 * 审计：audit-service 未建成，先以结构化日志落 AUDIT 事件（不含密码/哈希），建成后迁移。
 */
@Component
public class StudentAccountService {

    private static final Logger log = LoggerFactory.getLogger(StudentAccountService.class);

    public record CreatedStudent(String studentId, String studentNo, String username,
                                 String displayName, boolean passwordChangeRequired) {}

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public StudentAccountService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
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
        if (user == null || !"CANDIDATE".equals(user.getRole())) {
            return null;
        }
        return new CreatedStudent(user.getId(), user.getStudentNo(), user.getUsername(),
                user.getDisplayName(), Boolean.TRUE.equals(user.getPasswordChangeRequired()));
    }

    public CreatedStudent createStudent(String studentNo, String username, String displayName,
                                        String initialPassword, String operatorTeacherId) {
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

        UserEntity entity = new UserEntity();
        entity.setId(java.util.UUID.randomUUID().toString());
        entity.setUsername(username);
        entity.setPasswordHash(passwordEncoder.encode(initialPassword));
        entity.setDisplayName(displayName);
        entity.setRole("CANDIDATE");
        entity.setStatus("ACTIVE");
        entity.setStudentNo(studentNo);
        entity.setManagedByTeacherId(operatorTeacherId);
        entity.setPasswordChangeRequired(true);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        userMapper.insert(entity);

        log.info("AUDIT event=STUDENT_CREATED studentId={} studentNo={} managedBy={}",
                entity.getId(), studentNo, operatorTeacherId);
        return new CreatedStudent(entity.getId(), studentNo, username, displayName, true);
    }

    /** 重置学生密码：新临时密码立即哈希，置 passwordChangeRequired；不返回任何密码材料。 */
    public void resetStudentPassword(String studentId, String temporaryPassword, String operatorTeacherId) {
        if (temporaryPassword == null || temporaryPassword.length() < 8) {
            throw new UserException.WeakPassword();
        }
        UserEntity user = userMapper.selectById(studentId);
        if (user == null) {
            throw new UserException.NotFound();
        }
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setPasswordChangeRequired(true);
        user.setManagedByTeacherId(operatorTeacherId);
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        log.info("AUDIT event=STUDENT_PASSWORD_RESET studentId={} managedBy={}", studentId, operatorTeacherId);
    }
}
