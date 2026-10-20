package hpas.db.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 门诊信函（P10 信函编制、批准与分发）。写入者：{@code letter-dispatch}。
 *
 * <p>业务主键 {@code case_id}（BPMN 变量 {@code caseId}）；{@code dispatchedWithin7Days}
 * 就是 BR-27 的 7 天时限结论，落库后才能做「超时比例」这类管理报表。
 */
@Entity
@Table(name = "clinic_letter", indexes = {
        @Index(name = "idx_letter_case_id", columnList = "case_id", unique = true)
})
public class ClinicLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BPMN 变量: caseId */
    @Column(name = "case_id", nullable = false, unique = true, length = 128)
    private String caseId;

    /** P10 变量: patientName */
    @Column(name = "patient_name", length = 200)
    private String patientName;

    /** P10 变量: letterForm.letterType */
    @Column(name = "letter_type", length = 128)
    private String letterType;

    /** P10 变量: letterForm.letterBody */
    @Column(name = "body", length = 4000)
    private String body;

    /** P10 变量: checksForm.addressee */
    @Column(name = "addressee", length = 256)
    private String addressee;

    /** P10 变量: dispatchedAt（letter-dispatch 输出） */
    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    /** P10 变量: letterDispatched */
    @Column(name = "status", length = 64)
    private String status;

    /** P10 变量: elapsedDays */
    @Column(name = "elapsed_days")
    private Integer elapsedDays;

    /** P10 变量: dispatchedWithin7Days（BR-27） */
    @Column(name = "dispatched_within7_days")
    private Boolean dispatchedWithin7Days;

    /** P10 变量: escalationForm.escalationReason（超时升级时写入） */
    @Column(name = "escalation_reason", length = 1000)
    private String escalationReason;

    /** P10 变量: escalationForm.decision */
    @Column(name = "escalation_decision", length = 64)
    private String escalationDecision;

    @Column(name = "escalated_at")
    private OffsetDateTime escalatedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getLetterType() {
        return letterType;
    }

    public void setLetterType(String letterType) {
        this.letterType = letterType;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getAddressee() {
        return addressee;
    }

    public void setAddressee(String addressee) {
        this.addressee = addressee;
    }

    public OffsetDateTime getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(OffsetDateTime dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getElapsedDays() {
        return elapsedDays;
    }

    public void setElapsedDays(Integer elapsedDays) {
        this.elapsedDays = elapsedDays;
    }

    public Boolean getDispatchedWithin7Days() {
        return dispatchedWithin7Days;
    }

    public void setDispatchedWithin7Days(Boolean dispatchedWithin7Days) {
        this.dispatchedWithin7Days = dispatchedWithin7Days;
    }

    public String getEscalationReason() {
        return escalationReason;
    }

    public void setEscalationReason(String escalationReason) {
        this.escalationReason = escalationReason;
    }

    public String getEscalationDecision() {
        return escalationDecision;
    }

    public void setEscalationDecision(String escalationDecision) {
        this.escalationDecision = escalationDecision;
    }

    public OffsetDateTime getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(OffsetDateTime escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
