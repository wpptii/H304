package hpas.db.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/**
 * 独立的流程审计轨迹（不是 Camunda 的操作日志）。
 *
 * <p>每个 {@code @JobWorker} 执行完都会写一行：谁（performedBy）、在哪个流程实例 / 元素 / 作业上
 * （processInstanceKey / elementId / jobKey）、对哪个业务对象（businessKey）、做了什么（action）。
 *
 * <p>为什么数据库比 Operate 更合适：Operate 的审计按流程实例保留、随实例清理策略消失，
 * 也不方便和业务表 JOIN 做「某患者全部操作」这种查询。
 *
 * <p>{@code uq_audit_dedup(process_instance_key, job_key, action)} 让作业重试时的重复审计
 * 保持幂等，不会因为 Worker 重跑而炸出重复行或异常。
 */
@Entity
@Table(name = "process_audit",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_audit_dedup",
                        columnNames = {"process_instance_key", "job_key", "action"})
        },
        indexes = {
                @Index(name = "idx_audit_business_key", columnList = "business_key"),
                @Index(name = "idx_audit_job_type", columnList = "job_type"),
                @Index(name = "idx_audit_process_instance", columnList = "process_instance_key")
        })
public class ProcessAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ActivatedJob#getProcessInstanceKey() */
    @Column(name = "process_instance_key")
    private Long processInstanceKey;

    /** ActivatedJob#getBpmnProcessId() —— 即 processDefinitionId */
    @Column(name = "process_definition_id", length = 200)
    private String processDefinitionId;

    /** ActivatedJob#getProcessDefinitionKey() */
    @Column(name = "process_definition_key")
    private Long processDefinitionKey;

    /** ActivatedJob#getElementId() */
    @Column(name = "element_id", length = 128)
    private String elementId;

    /** ActivatedJob#getType() —— 31 个 job type 之一 */
    @Column(name = "job_type", length = 128)
    private String jobType;

    /** ActivatedJob#getKey() */
    @Column(name = "job_key")
    private Long jobKey;

    /** 执行者：{@code @JobWorker} 未设置 worker name 时用 service 标识，可靠表区分「人 / Worker」 */
    @Column(name = "performed_by", length = 128)
    private String performedBy;

    @Column(name = "performed_at")
    private OffsetDateTime performedAt;

    /** 业务动作，如 referral-completeness-check / record-payment */
    @Column(name = "action", length = 128)
    private String action;

    /** 业务主键：referralId / appointmentId / caseId / enquiryId */
    @Column(name = "business_key", length = 128)
    private String businessKey;

    @Column(name = "details", length = 2000)
    private String details;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProcessInstanceKey() {
        return processInstanceKey;
    }

    public void setProcessInstanceKey(Long processInstanceKey) {
        this.processInstanceKey = processInstanceKey;
    }

    public String getProcessDefinitionId() {
        return processDefinitionId;
    }

    public void setProcessDefinitionId(String processDefinitionId) {
        this.processDefinitionId = processDefinitionId;
    }

    public Long getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public void setProcessDefinitionKey(Long processDefinitionKey) {
        this.processDefinitionKey = processDefinitionKey;
    }

    public String getElementId() {
        return elementId;
    }

    public void setElementId(String elementId) {
        this.elementId = elementId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public Long getJobKey() {
        return jobKey;
    }

    public void setJobKey(Long jobKey) {
        this.jobKey = jobKey;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(String performedBy) {
        this.performedBy = performedBy;
    }

    public OffsetDateTime getPerformedAt() {
        return performedAt;
    }

    public void setPerformedAt(OffsetDateTime performedAt) {
        this.performedAt = performedAt;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
