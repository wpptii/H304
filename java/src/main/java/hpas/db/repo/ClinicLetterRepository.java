package hpas.db.repo;

import hpas.db.domain.ClinicLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 信函仓储。 */
public interface ClinicLetterRepository extends JpaRepository<ClinicLetter, Long> {

    Optional<ClinicLetter> findByCaseId(String caseId);

    List<ClinicLetter> findTop100ByOrderByIdDesc();

    long countByDispatchedWithin7Days(Boolean withinDeadline);
}
