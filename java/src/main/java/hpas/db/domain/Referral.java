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
 * 转诊单（P1 转诊接收 / P2 临床审核的主数据）。
 *
 * <p>业务主键 {@code referralId}（BPMN 变量 {@code referralId}，P1 表单 p1-start-referral 录入），
 * 数据库层面加唯一约束 —— 流程变量做不到这一点。
 */
@Entity
@Table(name = "referral", indexes = {
        @Index(name = "idx_referral_referral_id", columnList = "referral_id", unique = true),
        @Index(name = "idx_referral_status", columnList = "status")
})
public class Referral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BPMN: referralId */
    @Column(name = "referral_id", nullable = false, unique = true, length = 128)
    private String referralId;

    /** P1 表单变量: patientName */
    @Column(name = "patient_name", length = 200)
    private String patientName;

    /** 可选变量: patientDob（当前表单未采集，允许为空） */
    @Column(name = "patient_dob")
    private LocalDate patientDob;

    /** P1 变量: referralSource（gp / other-hospital / internal / self） */
    @Column(name = "referral_source", length = 64)
    private String referralSource;

    /** 建档时间（数据库自己记录，与流程变量 checkedAt 无关） */
    @Column(name = "received_date")
    private LocalDate receivedDate;

    /** referral-completeness-check 输出: isComplete -> COMPLETE / INCOMPLETE */
    @Column(name = "completeness_status", length = 32)
    private String completenessStatus;

    /** referral-completeness-check 输出: missingItems（逗号分隔） */
    @Column(name = "missing_items", length = 2000)
    private String missingItems;

    /** 流程阶段: RECEIVED / INCOMPLETE / COMPLETE / UNDER_CLINICAL_REVIEW / DECIDED_* ... */
    @Column(name = "status", length = 64)
    private String status;

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

    public String getReferralId() {
        return referralId;
    }

    public void setReferralId(String referralId) {
        this.referralId = referralId;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public LocalDate getPatientDob() {
        return patientDob;
    }

    public void setPatientDob(LocalDate patientDob) {
        this.patientDob = patientDob;
    }

    public String getReferralSource() {
        return referralSource;
    }

    public void setReferralSource(String referralSource) {
        this.referralSource = referralSource;
    }

    public LocalDate getReceivedDate() {
        return receivedDate;
    }

    public void setReceivedDate(LocalDate receivedDate) {
        this.receivedDate = receivedDate;
    }

    public String getCompletenessStatus() {
        return completenessStatus;
    }

    public void setCompletenessStatus(String completenessStatus) {
        this.completenessStatus = completenessStatus;
    }

    public String getMissingItems() {
        return missingItems;
    }

    public void setMissingItems(String missingItems) {
        this.missingItems = missingItems;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
