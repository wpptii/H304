package hpas.db;

import hpas.db.domain.AppUser;
import hpas.db.domain.UserRole;
import hpas.db.repo.AppUserRepository;
import hpas.db.repo.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 启动时播种「用户 + 角色 / 组」数据（幂等；可用 {@code -Dspring.profiles.active=no-seed} 关闭）。
 *
 * <p>这样数据库里就有真正的权限模型：谁能做临床审核（BR-01）、谁能看财务咨询（BR-31）、
 * 谁是外部 Pool（GP / 排班 / PSP / 资助机构）。
 */
@Component
@Profile("!no-seed")
public class DatabaseSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseSeeder.class);

    private static final String[][] USERS = {
            // username, displayName, department
            {"secretary01", "Medical Secretary", "Referral Intake"},
            {"consultant01", "Consultant Physician", "Clinical Review"},
            {"scheduler01", "Booking Team", "Scheduling"},
            {"finance01", "Finance Officer", "Funding"},
            {"psp01", "Payment Service Provider", "External PSP"},
            {"gpclinic01", "GP Clinic Contact", "External GP"},
            {"coordinator01", "Pathway Coordinator", "Management"},
    };

    private static final String[][] ROLES = {
            // username, role, group
            {"secretary01", "secretary", "hospital-pool"},
            {"secretary01", "registrar", "hospital-pool"},
            {"consultant01", "consultant", "hospital-pool"},
            {"scheduler01", "scheduler", "scheduler-pool"},
            {"finance01", "finance", "hospital-pool"},
            {"psp01", "psp", "psp-pool"},
            {"gpclinic01", "external-gp", "gp-pool"},
            {"coordinator01", "management", "management-pool"},
    };

    private final AppUserRepository users;
    private final UserRoleRepository roles;

    public DatabaseSeeder(AppUserRepository users, UserRoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            for (String[] u : USERS) {
                if (users.existsByUsername(u[0])) {
                    continue;
                }
                AppUser user = new AppUser();
                user.setUsername(u[0]);
                user.setDisplayName(u[1]);
                user.setDepartment(u[2]);
                user.setPasswordHash("{demo-plain}" + u[0]);
                user.setEnabled(true);
                user.setCreatedAt(OffsetDateTime.now());
                users.save(user);
            }
            for (String[] r : ROLES) {
                if (roles.existsByUsernameAndRoleAndGroupName(r[0], r[1], r[2])) {
                    continue;
                }
                UserRole role = new UserRole();
                role.setUsername(r[0]);
                role.setRole(r[1]);
                role.setGroupName(r[2]);
                role.setGrantedAt(OffsetDateTime.now());
                roles.save(role);
            }
            LOG.info("DatabaseSeeder: users={} roles={}", users.count(), roles.count());
        } catch (RuntimeException e) {
            LOG.warn("DatabaseSeeder skipped (database unavailable; workers are unaffected): {}", e.toString());
        }
    }
}
