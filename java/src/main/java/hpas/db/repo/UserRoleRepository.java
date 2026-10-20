package hpas.db.repo;

import hpas.db.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 角色 / 组仓储。 */
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    List<UserRole> findByUsername(String username);

    List<UserRole> findByGroupName(String groupName);

    boolean existsByUsernameAndRoleAndGroupName(String username, String role, String groupName);
}
