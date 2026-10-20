package hpas.db.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/**
 * 用户角色 / 组（Camunda Tasklist 的 role / group 在数据库里的对应物）。
 *
 * <p>一个问题一行（例如 secretary + 医院 Pool），用 {@code username + role + group_name} 唯一约束
 * 保证不会重复授权。这样「秘书不能做临床评估」「财务只能看财务咨询」这类业务规则
 * （BR-01 / BR-31）就有了数据依据，而不只是 UI 上的约定。
 */
@Entity
@Table(name = "user_role",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_user_role", columnNames = {"username", "role", "group_name"})
        },
        indexes = {
                @Index(name = "idx_role_username", columnList = "username"),
                @Index(name = "idx_role_group", columnList = "group_name")
        })
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** -> app_user.username */
    @Column(name = "username", nullable = false, length = 128)
    private String username;

    /** 角色，如 secretary / consultant / finance / scheduler / psp / admin */
    @Column(name = "role", nullable = false, length = 64)
    private String role;

    /** 泳道 / 权限组，如 hospital-pool / gp-pool / partner-pool / psp-pool / funder-pool */
    @Column(name = "group_name", length = 128)
    private String groupName;

    @Column(name = "granted_at")
    private OffsetDateTime grantedAt;

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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public OffsetDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(OffsetDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }
}
