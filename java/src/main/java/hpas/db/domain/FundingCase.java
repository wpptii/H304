package hpas.db.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 资助案例（P7 资助确定与记录）。写入者：{@code funding-validation} / {@code funder-send-result}
 * / {@code request-funding-approval} 路径。
 *
 * <p>{@code case_id} 唯一；{@code referral_id} / {@code appointment_id} 是业务外键，
 * 数据库可以用 FK 约束保证「不会出现指向不存在预约的资助记录」。
 */
@Entity
@Table(name = "funding_case", indexes = {
        @Index(name = "idx_funding_case_id", columnList = "case_id", unique = true),
        @Index(name = "idx_funding_referral_id", columnList = "referral_id"),
        @Index(name = "idx_funding_appointment_id", columnList = "appointment_id")
})
public class FundingCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BPMN/业务变量: caseId */
    @Column(name = "case_id", nullable = false, unique = true, length = 128)
    private String caseId;

    @Column(name = "referral_id", length = 128)
    private String referralId;

    /** P7 关联键 */
    @Column(name = "appointment_id", length = 128)
    private String appointmentId;

    /** P7 变量: fundingSource（self-pay / insurance / funding-agency / exempt） */
    @Column(name = "funding_source", length = 64)
    private String fundingSource;

    /** P7 变量: funder 回执里的 approvalReference / authorizationRef */
    @Column(name = "approval_reference", length = 128)
    private String approvalReference;

    /** P7 变量: approvedAmount */
    @Column(name = "approved_amount", precision = 14, scale = 2)
    private BigDecimal approvedAmount;

    /** BPMN 变量: approvalStatus（approved / rejected / pending） */
    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    /** P7 变量: restrictions */
    @Column(name = "restrictions", length = 2000)
    private String restrictions;

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

    public String getReferralId() {
        return referralId;
    }

    public void setReferralId(String referralId) {
        this.referralId = referralId;
    }

    public String getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(String appointmentId) {
        this.appointmentId = appointmentId;
    }

    public String getFundingSource() {
        return fundingSource;
    }

    public void setFundingSource(String fundingSource) {
        this.fundingSource = fundingSource;
    }

    public String getApprovalReference() {
        return approvalReference;
    }

    public void setApprovalReference(String approvalReference) {
        this.approvalReference = approvalReference;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public void setApprovedAmount(BigDecimal approvedAmount) {
        this.approvedAmount = approvedAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(OffsetDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRestrictions() {
        return restrictions;
    }

    public void setRestrictions(String restrictions) {
        this.restrictions = restrictions;
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
