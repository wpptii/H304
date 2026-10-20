package hpas.db.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 临床审核决定（P2）。写入者：{@code clinical-decision-audit}。
 *
 * <p>取 BPMN 变量 {@code reviewForm.decision} / {@code reviewForm.reason} / {@code reviewForm.decidedBy}
 * 与 {@code referralId}；同一转诊单只保留最新一次决定（{@code referral_id} 唯一）。
 */
@Entity
@Table(name = "clinical_decision", indexes = {
        @Index(name = "idx_decision_referral_id", columnList = "referral_id", unique = true)
})
public class ClinicalDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "referral_id", nullable = false, unique = true, length = 128)
    private String referralId;

    /** BPMN 变量: decidedBy */
    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    /** BPMN 变量: decision（accept / reject / refer-other ...） */
    @Column(name = "decision", length = 64)
    private String decision;

    /** BPMN 变量: reason */
    @Column(name = "reason", length = 2000)
    private String reason;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

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

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(OffsetDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }
}
