package hpas.db.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 应用用户（把「角色 / 岗位」也纳入数据模型）。
 *
 * <p>与流程里的 {@code registeredBy} / {@code decidedBy} / {@code handedOverTo} 等变量对应：
 * 这些变量只存名字，落库后才能回答「谁有权做临床审核（BR-01 秘书不得临床评估）」。
 * 账号由 {@code hpas.db.DatabaseSeeder} 在启动时播种。
 */
@Entity
@Table(name = "app_user", indexes = {
        @Index(name = "idx_user_username", columnList = "username", unique = true)
})
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 128)
    private String username;

    /** 明文/散列均可；演示数据用明文并标注，真实系统必须散列 */
    @Column(name = "password_hash", length = 200)
    private String passwordHash;

    @Column(name = "display_name", length = 200)
    private String displayName;

    /** 员工所在科室 / 岗位描述 */
    @Column(name = "department", length = 128)
    private String department;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
