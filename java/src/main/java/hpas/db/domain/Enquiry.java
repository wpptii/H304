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
 * 患者咨询（P8 咨询处理与路由）。写入者：{@code enquiry-triage} /
 * {@code route-to-finance} / {@code route-to-clinical} / {@code await-finance-response}。
 */
@Entity
@Table(name = "enquiry", indexes = {
        @Index(name = "idx_enquiry_enquiry_id", columnList = "enquiry_id", unique = true)
})
public class Enquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BPMN 变量: enquiryId */
    @Column(name = "enquiry_id", nullable = false, unique = true, length = 128)
    private String enquiryId;

    /** P8 变量: enquiryForm.inquiryType */
    @Column(name = "inquiry_type", length = 64)
    private String inquiryType;

    /** P8 变量: enquiryForm.content */
    @Column(name = "content", length = 4000)
    private String content;

    @Column(name = "patient_name", length = 200)
    private String patientName;

    /** P8 变量: triageUrgency（urgent / routine） */
    @Column(name = "urgency", length = 32)
    private String urgency;

    /** P8 变量: triageNote */
    @Column(name = "triage_note", length = 1000)
    private String triageNote;

    /** P8 变量: urgentKeywords */
    @Column(name = "urgent_keywords", length = 1000)
    private String urgentKeywords;

    /** TRIAGED / ROUTED_FINANCE / ROUTED_CLINICAL / HANDLED */
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

    public String getEnquiryId() {
        return enquiryId;
    }

    public void setEnquiryId(String enquiryId) {
        this.enquiryId = enquiryId;
    }

    public String getInquiryType() {
        return inquiryType;
    }

    public void setInquiryType(String inquiryType) {
        this.inquiryType = inquiryType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getUrgency() {
        return urgency;
    }

    public void setUrgency(String urgency) {
        this.urgency = urgency;
    }

    public String getTriageNote() {
        return triageNote;
    }

    public void setTriageNote(String triageNote) {
        this.triageNote = triageNote;
    }

    public String getUrgentKeywords() {
        return urgentKeywords;
    }

    public void setUrgentKeywords(String urgentKeywords) {
        this.urgentKeywords = urgentKeywords;
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
