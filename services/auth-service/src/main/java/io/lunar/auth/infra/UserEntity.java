package io.lunar.auth.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * auth_schema.users 的持久化对象。id 由应用侧生成（uuid 主键无库端默认值）；
 * created_at/updated_at 由数据库默认值维护，仅在插入时需要显式赋值的场景手动设置。
 */
@TableName("auth_schema.users")
public class UserEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String username;

    private String passwordHash;

    private String displayName;

    private String role;

    private String status;

    private String studentNo;

    private String managedByTeacherId;

    private Boolean passwordChangeRequired;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getManagedByTeacherId() { return managedByTeacherId; }
    public void setManagedByTeacherId(String managedByTeacherId) { this.managedByTeacherId = managedByTeacherId; }
    public Boolean getPasswordChangeRequired() { return passwordChangeRequired; }
    public void setPasswordChangeRequired(Boolean passwordChangeRequired) { this.passwordChangeRequired = passwordChangeRequired; }
}
