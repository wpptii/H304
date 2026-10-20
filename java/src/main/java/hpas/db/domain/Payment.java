package hpas.db.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 支付（P12）。写入者：{@code psp-process-payment} / {@code psp-send-status} / {@code payment-retry}。
 *
 * <p>关键约束（流程变量做不到的）：
 * <ul>
 *   <li>{@code psp_reference} 唯一 —— PSP 的回执号不可能重复入库；</li>
 *   <li>{@code uq_payment_case_status} UNIQUE (case_id, status) —— 数据库层面保证
 *       同一个 caseId 的同一个结论只可能有一行；「同一 caseId 不得有两笔成功支付」
 *       由它加上 {@code PersistenceService.recordPayment} 的业务判断共同保证。</li>
 * </ul>
 */
@Entity
@Table(name = "payment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_payment_case_status", columnNames = {"case_id", "status"})
        },
        indexes = {
                @Index(name = "idx_payment_psp_reference", columnList = "psp_reference", unique = true),
                @Index(name = "idx_payment_case_id", columnList = "case_id"),
                @Index(name = "idx_payment_idempotency_key", columnList = "idempotency_key", unique = true)
        })
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BPMN 变量: caseId（P12 关联键） */
    @Column(name = "case_id", nullable = false, length = 128)
    private String caseId;

    /** BPMN 变量: amount（paymentForm.amount） */
    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

    /** 默认 CNY（表单只采集金额，不采集币种） */
    @Column(name = "currency", length = 8)
    private String currency;

    /** BPMN 变量: paymentMethod */
    @Column(name = "method", length = 64)
    private String method;

    /** PSP 回执号：psp-process-payment 输出 */
    @Column(name = "psp_reference", length = 128)
    private String pspReference;

    /** success / failed / duplicate / cancelled */
    @Column(name = "status", length = 32)
    private String status;

    /** 幂等键（caseId + pspReference + status），保证同一事件的重复投递只落一行 */
    @Column(name = "idempotency_key", length = 256)
    private String idempotencyKey;

    /** BPMN 变量: failureReason */
    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPspReference() {
        return pspReference;
    }

    public void setPspReference(String pspReference) {
        this.pspReference = pspReference;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(OffsetDateTime paidAt) {
        this.paidAt = paidAt;
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
