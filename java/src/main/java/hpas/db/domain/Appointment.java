package hpas.db.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 预约（P3 排程 + P4 患者通知）。
 *
 * <p>写入者：{@code scheduler-process-request}（号源结果）、{@code notify-notification-team}
 * （预约日期时间）、{@code communication-send} / {@code contact-log}（通知渠道与尝试次数）。
 */
@Entity
@Table(name = "appointment", indexes = {
        @Index(name = "idx_appointment_appointment_id", columnList = "appointment_id", unique = true),
        @Index(name = "idx_appointment_referral_id", columnList = "referral_id")
})
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BPMN 变量: appointmentId（P4 / P7 的关联键） */
    @Column(name = "appointment_id", nullable = false, unique = true, length = 128)
    private String appointmentId;

    /** 业务外键 -> referral.referral_id（P3 的关联键是 referralId） */
    @Column(name = "referral_id", length = 128)
    private String referralId;

    @Column(name = "specialty", length = 128)
    private String specialty;

    /** BPMN 变量: priority */
    @Column(name = "priority", length = 32)
    private String priority;

    /** P3 变量: appointmentDateTime（notify-notification-team 的 confirmForm.appointmentDateTime） */
    @Column(name = "appointment_date_time")
    private LocalDateTime appointmentDateTime;

    /** P3 变量: slotReference（scheduler-process-request 输出） */
    @Column(name = "slot_reference", length = 128)
    private String slotReference;

    /** PROPOSED / CONFIRMED / NOTIFIED / CANCELLED */
    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    /** P4 变量: notifyChannel */
    @Column(name = "notification_channel", length = 128)
    private String notificationChannel;

    /** P4 变量: notifyAttempts */
    @Column(name = "notification_attempts")
    private Integer notificationAttempts;

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

    public String getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(String appointmentId) {
        this.appointmentId = appointmentId;
    }

    public String getReferralId() {
        return referralId;
    }

    public void setReferralId(String referralId) {
        this.referralId = referralId;
    }

    public String getSpecialty() {
        return specialty;
    }

    public void setSpecialty(String specialty) {
        this.specialty = specialty;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public LocalDateTime getAppointmentDateTime() {
        return appointmentDateTime;
    }

    public void setAppointmentDateTime(LocalDateTime appointmentDateTime) {
        this.appointmentDateTime = appointmentDateTime;
    }

    public String getSlotReference() {
        return slotReference;
    }

    public void setSlotReference(String slotReference) {
        this.slotReference = slotReference;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(OffsetDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getNotificationChannel() {
        return notificationChannel;
    }

    public void setNotificationChannel(String notificationChannel) {
        this.notificationChannel = notificationChannel;
    }

    public Integer getNotificationAttempts() {
        return notificationAttempts;
    }

    public void setNotificationAttempts(Integer notificationAttempts) {
        this.notificationAttempts = notificationAttempts;
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
