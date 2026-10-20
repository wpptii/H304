package hpas.db.repo;

import hpas.db.domain.ProcessAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 流程审计仓储。 */
public interface ProcessAuditRepository extends JpaRepository<ProcessAudit, Long> {

    List<ProcessAudit> findTop200ByOrderByIdDesc();

    List<ProcessAudit> findByBusinessKeyOrderByIdAsc(String businessKey);

    List<ProcessAudit> findByJobTypeOrderByIdDesc(String jobType);

    /** 作业重试去重：同一个作业上的同一个动作只留一行。 */
    Optional<ProcessAudit> findFirstByProcessInstanceKeyAndJobKeyAndAction(
            Long processInstanceKey, Long jobKey, String action);

    boolean existsByProcessInstanceKeyAndJobKeyAndAction(Long processInstanceKey, Long jobKey, String action);

    long countByJobType(String jobType);
}
