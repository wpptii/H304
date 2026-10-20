package hpas.db.repo;

import hpas.db.domain.Referral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 转诊单仓储。 */
public interface ReferralRepository extends JpaRepository<Referral, Long> {

    Optional<Referral> findByReferralId(String referralId);

    boolean existsByReferralId(String referralId);

    List<Referral> findTop100ByOrderByIdDesc();

    long countByCompletenessStatus(String completenessStatus);
}
