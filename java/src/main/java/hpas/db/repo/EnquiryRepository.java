package hpas.db.repo;

import hpas.db.domain.Enquiry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 咨询仓储。 */
public interface EnquiryRepository extends JpaRepository<Enquiry, Long> {

    Optional<Enquiry> findByEnquiryId(String enquiryId);

    List<Enquiry> findTop100ByOrderByIdDesc();

    long countByUrgency(String urgency);
}
