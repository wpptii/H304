package hpas.db.repo;

import hpas.db.domain.FundingCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 资助案例仓储。 */
public interface FundingCaseRepository extends JpaRepository<FundingCase, Long> {

    Optional<FundingCase> findByCaseId(String caseId);

    List<FundingCase> findByAppointmentId(String appointmentId);

    List<FundingCase> findTop100ByOrderByIdDesc();

    long countByStatus(String status);
}
