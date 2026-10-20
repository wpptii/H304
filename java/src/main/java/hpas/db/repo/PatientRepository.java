package hpas.db.repo;

import hpas.db.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 患者主数据仓储。 */
public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByPatientRef(String patientRef);
}
