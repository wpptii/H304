package hpas.db.repo;

import hpas.db.domain.ClinicalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 临床审核决定仓储。 */
public interface ClinicalDecisionRepository extends JpaRepository<ClinicalDecision, Long> {

    Optional<ClinicalDecision> findByReferralId(String referralId);

    List<ClinicalDecision> findTop100ByOrderByIdDesc();

    long countByDecision(String decision);
}
