package hpas.db.repo;

import hpas.db.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 支付仓储（含「同一 caseId 是否已有成功支付」的重复扣款查询）。 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPspReference(String pspReference);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByCaseIdOrderByIdAsc(String caseId);

    List<Payment> findTop100ByOrderByIdDesc();

    /** 重复扣款检查：该 caseId 是否已经有一笔成功支付。 */
    Optional<Payment> findFirstByCaseIdAndStatus(String caseId, String status);

    boolean existsByCaseIdAndStatus(String caseId, String status);

    long countByStatus(String status);
}
