package hpas.db.repo;

import hpas.db.domain.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 预约仓储。 */
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByAppointmentId(String appointmentId);

    List<Appointment> findByReferralId(String referralId);

    List<Appointment> findTop100ByOrderByIdDesc();

    long countByStatus(String status);
}
