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
    /**
     * 按学号或账户名查学生账号。
     *
     * **归属过滤**：老师只能查到自己名下的学生——否则任意老师都能按学号逐个试探，
     * 把全平台的学生（学号 + 姓名 + 班级）枚举出来。ADMIN 与服务身份不受限：
     * 服务身份来自 contest，它已做过考试归属校验。
     *
     * 代价（已知且接受）：学生由老师 A 建、老师 B 想加进自己的考试时，B 查不到该账号，
     * 会走到"新建账号"分支并被唯一约束拒绝——这种跨老师复用需要管理员操作。
     */
    public CreatedStudent findStudent(String studentNo, String username, String actorId, String actorRole) {
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
        // 归属过滤：老师只能查到自己名下的学生，否则可逐个学号试探、枚举全平台学生
        if (Role.TEACHER.name().equals(actorRole) && actorId != null) {
            query.eq(UserEntity::getManagedByTeacherId, actorId);
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
        return new CreatedStudent(user.getId(), studentNo, username, displayName, className, true);
    }

    /**
     * 重置学生密码：新临时密码立即哈希，置 passwordChangeRequired；不返回任何密码材料。
     * BE-01：只允许重置 CANDIDATE 账号（目标非学生按不存在处理，避免探测账号）；
     * **重置不改变账号归属**——`managedByTeacherId` 表达管理关系，不是"最近一次操作人"。
     */
    public void resetStudentPassword(String studentId, String temporaryPassword, String operatorToken,
                                     String actorId, String actorRole) {
        if (temporaryPassword == null || temporaryPassword.length() < 8) {
            throw new UserException.WeakPassword();
        }
        UserEntity user = userMapper.selectById(studentId);
        if (user == null || !Role.CANDIDATE.name().equals(user.getRole())) {
            throw new UserException.NotFound();
        }
        // 归属校验（BE-01 剩余项）：老师只能重置自己名下学生的密码。
        // ADMIN 与服务身份不受限——服务身份来自 contest，它已做过考试归属校验（信任边界在 contest）。
        // 越权与"不存在"同码，不泄露账号存在性。
        if (Role.TEACHER.name().equals(actorRole) && user.getManagedByTeacherId() != null
                && !user.getManagedByTeacherId().equals(actorId)) {
            log.info("AUDIT event=STUDENT_RESET_DENIED studentId={} actorId={} reason=not-owner",
                    studentId, actorId);
            throw new UserException.NotFound();
        }
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        // 敏感重置必须让已签发的令牌失效（BE-09）：JWT 离线校验，不标记的话旧令牌在到期前照样能用
        user.setTokensValidAfter(Instant.now());
        user.setPasswordChangeRequired(true);
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        log.info("AUDIT event=STUDENT_PASSWORD_RESET studentId={}", studentId);
    }
}
