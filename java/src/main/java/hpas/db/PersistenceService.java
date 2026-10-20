package hpas.db;

import hpas.db.domain.Appointment;
import hpas.db.domain.ClinicLetter;
import hpas.db.domain.ClinicalDecision;
import hpas.db.domain.Enquiry;
import hpas.db.domain.FundingCase;
import hpas.db.domain.Patient;
import hpas.db.domain.Payment;
import hpas.db.domain.ProcessAudit;
import hpas.db.domain.Referral;
import hpas.db.repo.AppointmentRepository;
import hpas.db.repo.ClinicLetterRepository;
import hpas.db.repo.ClinicalDecisionRepository;
import hpas.db.repo.EnquiryRepository;
import hpas.db.repo.FundingCaseRepository;
import hpas.db.repo.PatientRepository;
import hpas.db.repo.PaymentRepository;
import hpas.db.repo.ProcessAuditRepository;
import hpas.db.repo.ReferralRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 持久化门面：Job Worker 调用这里把「这一步学到的东西」落库。
 *
 * <p>设计原则（对应任务要求）：
 * <ol>
 *   <li><b>幂等</b>：所有方法都以业务键 upsert，作业重试 / 消息重复投递不会产生重复行；</li>
 *   <li><b>永不抛异常</b>：每个公开方法自己 try/catch，数据库故障只写日志，
 *       绝不让原来的流程变成 incident；</li>
 *   <li><b>只读流程变量，不写流程变量</b>：不改变任何现有输出，流程行为与加库之前完全一致；</li>
 *   <li><b>每次调用都留审计</b>：{@code process_audit} 记录谁在哪个作业上写了哪张表。</li>
 * </ol>
 */
@Service
public class PersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(PersistenceService.class);

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DUPLICATE = "duplicate";
    public static final String STATUS_CANCELLED = "cancelled";

    private final ReferralRepository referrals;
    private final PatientRepository patients;
    private final AppointmentRepository appointments;
    private final ClinicalDecisionRepository decisions;
    private final ClinicLetterRepository letters;
    private final FundingCaseRepository fundingCases;
    private final PaymentRepository payments;
    private final EnquiryRepository enquiries;
    private final ProcessAuditRepository audits;

    public PersistenceService(ReferralRepository referrals,
                              PatientRepository patients,
                              AppointmentRepository appointments,
                              ClinicalDecisionRepository decisions,
                              ClinicLetterRepository letters,
                              FundingCaseRepository fundingCases,
                              PaymentRepository payments,
                              EnquiryRepository enquiries,
                              ProcessAuditRepository audits) {
        this.referrals = referrals;
        this.patients = patients;
        this.appointments = appointments;
        this.decisions = decisions;
        this.letters = letters;
        this.fundingCases = fundingCases;
        this.payments = payments;
        this.enquiries = enquiries;
        this.audits = audits;
    }

    // ------------------------------------------------------------------ 转诊 + 患者

    /** P1：登记 / 更新转诊单（referralId 唯一），顺带登记患者主数据。 */
    public String recordReferral(Map<String, Object> vars, String status, JobContext ctx) {
        try {
            String referralId = businessKey(vars, ctx, "referralId");
            return recordReferral(referralId, vars, status, ctx);
        } catch (RuntimeException e) {
            warn("recordReferral", e);
            return null;
        }
    }

    /** 同上，显式指定业务键。 */
    public String recordReferral(String referralId, Map<String, Object> vars, String status, JobContext ctx) {
        if (referralId == null) {
            return null;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            Referral r = referrals.findByReferralId(referralId).orElseGet(Referral::new);
            if (r.getId() == null) {
                r.setReferralId(referralId);
                r.setReceivedDate(LocalDate.now());
                r.setCreatedAt(now);
                r.setStatus(status == null ? "RECEIVED" : status);
            }
            String patientName = Values.first(vars, null, "patientName", "patient",
                    "registerForm.patientName", "startForm.patientName");
            if (patientName != null) {
                r.setPatientName(Values.cut(patientName, 200));
            }
            String source = Values.first(vars, null, "referralSource",
                    Values.nested(vars, "registerForm", "referralSource", null));
            if (source != null) {
                r.setReferralSource(Values.cut(source, 64));
            }
            LocalDate dob = parseDate(Values.first(vars, null, "patientDob", "dateOfBirth"));
            if (dob != null) {
                r.setPatientDob(dob);
            }
            if (status != null) {
                r.setStatus(Values.cut(status, 64));
            }
            r.setUpdatedAt(now);
            r = referrals.save(r);

            upsertPatient(r.getPatientName(), dob, ctx);
            appendAudit(ctx, "record-referral", referralId,
                    "status=" + r.getStatus() + " source=" + r.getReferralSource());
            return r.getReferralId();
        } catch (RuntimeException e) {
            warn("recordReferral(" + referralId + ")", e);
            return null;
        }
    }

    /** P1：完整性核查结论 + 缺失项。 */
    public String recordCompletenessCheck(Map<String, Object> vars, JobContext ctx) {
        try {
            String referralId = businessKey(vars, ctx, "referralId");
            if (referralId == null) {
                return null;
            }
            Boolean complete = Values.bool(vars, "isComplete");
            String missing = joinMissing(vars.get("missingItems"));
            if (complete == null) {
                complete = missing == null;
            }
            String status = complete ? "COMPLETE" : "INCOMPLETE";

            OffsetDateTime now = OffsetDateTime.now();
            Referral r = referrals.findByReferralId(referralId).orElseGet(Referral::new);
            if (r.getId() == null) {
                r.setReferralId(referralId);
                r.setReceivedDate(LocalDate.now());
                r.setCreatedAt(now);
            }
            r.setPatientName(Values.cut(Values.first(vars, r.getPatientName(), "patientName", "patient"), 200));
            r.setReferralSource(Values.cut(Values.first(vars, r.getReferralSource(), "referralSource",
                    Values.nested(vars, "registerForm", "referralSource", null)), 64));
            r.setCompletenessStatus(status);
            r.setMissingItems(Values.cut(missing, 2000));
            r.setStatus(status);
            r.setUpdatedAt(now);
            referrals.save(r);

            appendAudit(ctx, "completeness-check", referralId,
                    "isComplete=" + complete + " missingItems=" + missing);
            return status;
        } catch (RuntimeException e) {
            warn("recordCompletenessCheck", e);
            return null;
        }
    }

    /** P1/P2/Gp-Pool：补件请求或补件回传（只留审计，必要时更新缺失项）。 */
    public void recordSupplement(Map<String, Object> vars, String direction, JobContext ctx) {
        try {
            String referralId = businessKey(vars, ctx, "referralId");
            if (referralId == null) {
                return;
            }
            String provided = Values.first(vars, null, "supplementProvided", "providedItems",
                    Values.nested(vars, "supplementForm", "providedItems", null),
                    Values.nested(vars, "partnerSupplementForm", "providedItems", null));
            String missing = joinMissing(vars.get("missingItems"));

            if (provided != null || missing != null) {
                OffsetDateTime now = OffsetDateTime.now();
                Optional<Referral> found = referrals.findByReferralId(referralId);
                Referral r = found.orElseGet(Referral::new);
                if (r.getId() == null) {
                    r.setReferralId(referralId);
                    r.setReceivedDate(LocalDate.now());
                    r.setCreatedAt(now);
                    r.setStatus("RECEIVED");
                }
                if (provided != null) {
                    // 补件到齐：缺失项清空，等待下次完整性核查给出正式结论
                    r.setMissingItems(null);
                    r.setCompletenessStatus("SUPPLEMENT_RECEIVED");
                    r.setStatus("SUPPLEMENT_RECEIVED");
                } else {
                    r.setMissingItems(Values.cut(missing, 2000));
                    r.setCompletenessStatus("INCOMPLETE");
                    r.setStatus("SUPPLEMENT_REQUESTED");
                }
                r.setUpdatedAt(now);
                referrals.save(r);
            }
            appendAudit(ctx, "supplement-" + direction, referralId,
                    "provided=" + provided + " missing=" + missing);
        } catch (RuntimeException e) {
            warn("recordSupplement", e);
        }
    }

    // ------------------------------------------------------------------ 临床决定

    /** P2：临床审核决定（referralId 唯一，保留最新结论）。 */
    public String recordClinicalDecision(Map<String, Object> vars, JobContext ctx) {
        try {
            String referralId = businessKey(vars, ctx, "referralId");
            if (referralId == null) {
                return null;
            }
            String decision = Values.first(vars, null, "decision",
                    Values.nested(vars, "reviewForm", "decision", null));
            String reason = Values.first(vars, null, "reason",
                    Values.nested(vars, "reviewForm", "reason", null));
            String decidedBy = Values.first(vars, null, "decidedBy",
                    Values.nested(vars, "reviewForm", "decidedBy", null));

            OffsetDateTime now = OffsetDateTime.now();
            ClinicalDecision d = decisions.findByReferralId(referralId).orElseGet(ClinicalDecision::new);
            if (d.getId() == null) {
                d.setReferralId(referralId);
            }
            d.setDecision(Values.cut(decision, 64));
            d.setReason(Values.cut(reason, 2000));
            d.setDecidedBy(Values.cut(decidedBy, 128));
            d.setDecidedAt(now);
            decisions.save(d);

            String status = switch (decision == null ? "" : decision.trim().toLowerCase()) {
                case "accept", "accepted", "approve", "approved" -> "DECIDED_ACCEPTED";
                case "reject", "rejected", "decline", "declined" -> "DECIDED_REJECTED";
                case "refer-other", "refer_other", "refer-other-hospital", "transfer" -> "DECIDED_REFERRED";
                case "supplement", "request-supplement" -> "DECIDED_SUPPLEMENT";
                default -> "DECIDED";
            };
            referrals.findByReferralId(referralId).ifPresent(r -> {
                r.setStatus(status);
                r.setUpdatedAt(now);
                referrals.save(r);
            });

            appendAudit(ctx, "record-clinical-decision", referralId,
                    "decision=" + decision + " by=" + decidedBy + " reason=" + Values.cut(reason, 200));
            return status;
        } catch (RuntimeException e) {
            warn("recordClinicalDecision", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 预约

    /** P3/P4：预约 upsert（appointmentId 唯一；缺失时用 referralId 派生，保证一行一预约）。 */
    public String upsertAppointment(Map<String, Object> vars, JobContext ctx) {
        try {
            String appointmentId = Values.first(vars, null, "appointmentId");
            if (appointmentId == null) {
                String referralId = businessKey(vars, ctx, "referralId");
                appointmentId = referralId == null ? null : "APPT-" + referralId;
            }
            if (appointmentId == null) {
                return null;
            }
            OffsetDateTime now = OffsetDateTime.now();
            Appointment a = appointments.findByAppointmentId(appointmentId).orElseGet(Appointment::new);
            if (a.getId() == null) {
                a.setAppointmentId(appointmentId);
                a.setCreatedAt(now);
            }
            String referralId = Values.first(vars, a.getReferralId(), "referralId", "appointmentReferralId");
            if (referralId != null) {
                a.setReferralId(Values.cut(referralId, 128));
            }
            a.setSpecialty(Values.cut(Values.first(vars, a.getSpecialty(), "specialty", "department"), 128));
            a.setPriority(Values.cut(Values.first(vars, a.getPriority(), "priority"), 32));
            a.setSlotReference(Values.cut(Values.first(vars, a.getSlotReference(), "slotReference"), 128));

            String dateTime = Values.first(vars, null, "appointmentDateTime",
                    Values.nested(vars, "confirmForm", "appointmentDateTime", null));
            LocalDateTime parsed = parseDateTime(dateTime);
            if (parsed != null) {
                a.setAppointmentDateTime(parsed);
                a.setConfirmedAt(now);
                a.setStatus("CONFIRMED");
            }

            String scheduleStatus = Values.first(vars, null, "scheduleStatus");
            if ("success".equalsIgnoreCase(String.valueOf(scheduleStatus)) && a.getStatus() == null) {
                a.setStatus("PROPOSED");
            } else if ("failed".equalsIgnoreCase(String.valueOf(scheduleStatus)) && a.getStatus() == null) {
                // 排程失败进 Timer 重试环：留一个中间状态，便于报表看出「卡在等号源」
                a.setStatus("PENDING_SLOT");
            }
            Integer attempts = Values.integer(vars, "notifyAttempts");
            if (attempts != null) {
                a.setNotificationAttempts(attempts);
            }
            String channel = Values.first(vars, a.getNotificationChannel(), "notifyChannel", "method");
            if (channel != null) {
                a.setNotificationChannel(Values.cut(channel, 128));
            }
            Boolean notified = Values.bool(vars, "notifyStatus");
            if (Boolean.TRUE.equals(notified)) {
                a.setStatus("NOTIFIED");
            }
            String contactResult = Values.first(vars, null, "contactResult");
            if ("reschedule".equalsIgnoreCase(String.valueOf(contactResult))) {
                a.setStatus("RESCHEDULE_REQUESTED");
            }
            a.setUpdatedAt(now);
            Appointment saved = appointments.save(a);

            appendAudit(ctx, "upsert-appointment", appointmentId,
                    "referralId=" + saved.getReferralId() + " slot=" + saved.getSlotReference()
                            + " status=" + saved.getStatus() + " attempts=" + saved.getNotificationAttempts());
            return saved.getAppointmentId();
        } catch (RuntimeException e) {
            warn("upsertAppointment", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 资助

    /** P7：资助案例 upsert（caseId 唯一）。 */
    public String recordFunding(Map<String, Object> vars, JobContext ctx) {
        try {
            String appointmentId = Values.first(vars, null, "appointmentId");
            String referralId = Values.first(vars, null, "referralId");
            String caseId = Values.first(vars, null, "caseId", "fundingCaseId");
            if (caseId == null) {
                caseId = appointmentId != null ? appointmentId : referralId;
            }
            if (caseId == null) {
                caseId = "CASE-" + (ctx == null || ctx.processInstanceKey() == null
                        ? "UNKNOWN" : ctx.processInstanceKey());
            }

            OffsetDateTime now = OffsetDateTime.now();
            FundingCase f = fundingCases.findByCaseId(caseId).orElseGet(FundingCase::new);
            if (f.getId() == null) {
                f.setCaseId(caseId);
                f.setCreatedAt(now);
            }
            if (referralId != null) {
                f.setReferralId(Values.cut(referralId, 128));
            }
            if (appointmentId != null) {
                f.setAppointmentId(Values.cut(appointmentId, 128));
            }
            String source = Values.first(vars, f.getFundingSource(), "fundingSource",
                    Values.nested(vars, "fundingForm", "fundingSource", null));
            if (source != null) {
                f.setFundingSource(Values.cut(source, 64));
            }
            String ref = Values.first(vars, f.getApprovalReference(), "approvalReference",
                    "authorizationRef", Values.nested(vars, "fundingForm", "authorizationRef", null));
            if (ref != null) {
                f.setApprovalReference(Values.cut(ref, 128));
            }
            BigDecimal amount = parseAmount(Values.first(vars, null, "approvedAmount", "amount",
                    Values.nested(vars, "fundingForm", "approvedAmount", null)));
            if (amount != null) {
                f.setApprovedAmount(amount);
            }
            String approvalStatus = Values.first(vars, null, "approvalStatus", "fundingStatus");
            if (approvalStatus != null) {
                String normalized = approvalStatus.trim().toLowerCase();
                f.setStatus(Values.cut(normalized, 64));
                if ("approved".equals(normalized) || "rejected".equals(normalized)) {
                    f.setApprovedAt(now);
                }
            } else if (f.getStatus() == null) {
                Boolean required = Values.bool(vars, "approvalRequired");
                Boolean valid = Values.bool(vars, "fundingValid");
                f.setStatus(Boolean.TRUE.equals(required) ? "pending" : (Boolean.TRUE.equals(valid) ? "self-pay" : "unverified"));
            }
            String restrictions = Values.first(vars, null, "restrictions",
                    Values.nested(vars, "fundingForm", "restrictions", null));
            if (restrictions != null) {
                f.setRestrictions(Values.cut(restrictions, 2000));
            }
            f.setUpdatedAt(now);
            FundingCase saved = fundingCases.save(f);

            appendAudit(ctx, "record-funding", caseId,
                    "appointmentId=" + saved.getAppointmentId() + " source=" + saved.getFundingSource()
                            + " amount=" + saved.getApprovedAmount() + " status=" + saved.getStatus());
            return saved.getCaseId();
        } catch (RuntimeException e) {
            warn("recordFunding", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 支付

    /**
     * P12：记录支付 + <b>重复扣款检测</b>。
     *
     * <p>返回的字符串告诉调用者落库结果：
     * <ul>
     *   <li>{@code stored:success} —— 成功支付首次入库（caseId 没有别的成功支付）；</li>
     *   <li>{@code stored:failed} / {@code stored:duplicate} —— 失败 / PSP 判定重复，作为痕迹入库；</li>
     *   <li>{@code action-only:&lt;handling&gt;} —— 重试 / 取消动作（没有支付结果），只写审计不写支付行；</li>
     *   <li>{@code duplicate-blocked:&lt;已有成功支付的 pspReference&gt;} ——
     *       <b>该 caseId 已有一笔成功支付，本次成功结果被拒绝二次入账</b>；</li>
     *   <li>{@code idempotent-repeat:&lt;pspReference&gt;} —— 同一个 PSP 回执重复到达，只更新不新增。</li>
     * </ul>
     */
    public String recordPayment(Map<String, Object> vars, JobContext ctx) {
        try {
            String caseId = Values.first(vars, null, "caseId");
            if (caseId == null) {
                caseId = businessKey(vars, ctx, "appointmentId", "referralId");
            }
            if (caseId == null) {
                caseId = "CASE-" + (ctx == null || ctx.processInstanceKey() == null
                        ? "UNKNOWN" : ctx.processInstanceKey());
            }
            String rawStatus = Values.first(vars, null, "paymentStatus", "status");
            String pspReference = Values.first(vars, null, "pspReference");

            // 重试 / 取消作业只有 handling 动作，没有支付结果：只留审计，不写一条假的支付行
            if (rawStatus == null && pspReference == null) {
                String action = Values.first(vars, null, "handling", "paymentRetryOutcome");
                appendAudit(ctx, "payment-retry-action", caseId,
                        "action=" + action + " pspReference=" + pspReference);
                return "action-only:" + action;
            }

            String status = normalizePaymentStatus(rawStatus,
                    Values.first(vars, null, "paymentRetryOutcome"));
            String failureReason = Values.first(vars, null, "failureReason", "notifyError");
            BigDecimal amount = parseAmount(Values.first(vars, null, "amount",
                    Values.nested(vars, "paymentForm", "amount", null)));
            String method = Values.first(vars, null, "paymentMethod",
                    Values.nested(vars, "paymentForm", "paymentMethod", null));
            String currency = Values.first(vars, null, "currency");
            String idempotencyKey = Values.cut(caseId + "|" + (pspReference == null ? "-" : pspReference)
                    + "|" + status, 256);

            // 1) 重复扣款检测（业务规则，优先级最高）：同一 caseId 已有成功支付 → 本次成功结果拒绝入账
            if (STATUS_SUCCESS.equals(status)) {
                Optional<Payment> already = payments.findFirstByCaseIdAndStatus(caseId, STATUS_SUCCESS);
                if (already.isPresent()) {
                    String existingRef = already.get().getPspReference();
                    appendAudit(ctx, "duplicate-payment-blocked", caseId,
                            "caseId already has a successful payment pspReference=" + existingRef
                                    + "; this pspReference=" + pspReference + " was not recorded");
                    LOG.warn("PersistenceService: duplicate successful payment blocked for caseId={} (existing={}, new={})",
                            caseId, existingRef, pspReference);
                    return "duplicate-blocked:" + existingRef;
                }
            }

            // 2) 幂等：同一幂等键已经落过库，直接复用，不新增行
            Optional<Payment> existing = payments.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Payment p = existing.get();
                p.setUpdatedAt(OffsetDateTime.now());
                payments.save(p);
                appendAudit(ctx, "record-payment", caseId,
                        "idempotent repeat pspReference=" + p.getPspReference() + " status=" + p.getStatus());
                return "idempotent-repeat:" + p.getPspReference();
            }

            // 3) 幂等（第二道）：同一个 caseId 的同一个结论只留一行（数据库上还有 uq_payment_case_status）
            Optional<Payment> sameOutcome = payments.findFirstByCaseIdAndStatus(caseId, status);
            if (sameOutcome.isPresent()) {
                Payment p = sameOutcome.get();
                p.setUpdatedAt(OffsetDateTime.now());
                payments.save(p);
                appendAudit(ctx, "record-payment", caseId,
                        "same outcome already stored pspReference=" + p.getPspReference() + " status=" + p.getStatus());
                return "idempotent-repeat:" + p.getPspReference();
            }

            OffsetDateTime now = OffsetDateTime.now();
            Payment p = new Payment();
            p.setCaseId(Values.cut(caseId, 128));
            p.setAmount(amount);
            p.setCurrency(Values.cut(currency == null ? "CNY" : currency, 8));
            p.setMethod(Values.cut(method, 64));
            p.setPspReference(Values.cut(pspReference, 128));
            p.setStatus(status);
            p.setIdempotencyKey(idempotencyKey);
            p.setFailureReason(Values.cut(failureReason, 1000));
            p.setPaidAt(STATUS_SUCCESS.equals(status) ? now : null);
            p.setCreatedAt(now);
            p.setUpdatedAt(now);
            Payment saved = payments.save(p);

            appendAudit(ctx, "record-payment", caseId,
                    "status=" + saved.getStatus() + " pspReference=" + saved.getPspReference()
                            + " amount=" + saved.getAmount() + " reason=" + saved.getFailureReason());
            return "stored:" + saved.getStatus();
        } catch (RuntimeException e) {
            warn("recordPayment", e);
            return null;
        }
    }

    /** 重复扣款检查（只读，供 API / demo 查询使用）。 */
    public boolean hasSuccessfulPayment(String caseId) {
        try {
            return payments.existsByCaseIdAndStatus(caseId, STATUS_SUCCESS);
        } catch (RuntimeException e) {
            warn("hasSuccessfulPayment", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 信函 / 咨询

    /** P10：信函分发记录（caseId 唯一）。 */
    public String recordLetter(Map<String, Object> vars, JobContext ctx) {
        try {
            String caseId = Values.first(vars, null, "caseId");
            if (caseId == null) {
                caseId = businessKey(vars, ctx, "referralId", "appointmentId");
            }
            if (caseId == null) {
                return null;
            }
            OffsetDateTime now = OffsetDateTime.now();
            ClinicLetter l = letters.findByCaseId(caseId).orElseGet(ClinicLetter::new);
            if (l.getId() == null) {
                l.setCaseId(caseId);
                l.setCreatedAt(now);
            }
            l.setPatientName(Values.cut(Values.first(vars, l.getPatientName(), "patientName"), 200));
            l.setLetterType(Values.cut(Values.first(vars, l.getLetterType(), "letterType",
                    Values.nested(vars, "letterForm", "letterType", null)), 128));
            l.setBody(Values.cut(Values.first(vars, l.getBody(), "letterBody",
                    Values.nested(vars, "letterForm", "letterBody", null)), 4000));
            l.setAddressee(Values.cut(Values.first(vars, l.getAddressee(), "addressee",
                    Values.nested(vars, "checksForm", "addressee", null)), 256));
            Boolean dispatched = Values.bool(vars, "letterDispatched");
            if (Boolean.TRUE.equals(dispatched)) {
                l.setStatus("DISPATCHED");
                l.setDispatchedAt(now);
            } else if (l.getStatus() == null) {
                l.setStatus("DRAFT");
            }
            Integer elapsed = Values.integer(vars, "elapsedDays");
            if (elapsed != null) {
                l.setElapsedDays(elapsed);
            }
            Boolean within7 = Values.bool(vars, "dispatchedWithin7Days");
            if (within7 != null) {
                l.setDispatchedWithin7Days(within7);
            }
            l.setUpdatedAt(now);
            ClinicLetter saved = letters.save(l);

            appendAudit(ctx, "record-letter", caseId,
                    "type=" + saved.getLetterType() + " status=" + saved.getStatus()
                            + " elapsedDays=" + saved.getElapsedDays() + " within7=" + saved.getDispatchedWithin7Days());
            return saved.getCaseId();
        } catch (RuntimeException e) {
            warn("recordLetter", e);
            return null;
        }
    }

    /** P10：信函超时升级（只更新 clinic_letter 的升级字段 + 审计）。 */
    public void recordEscalation(Map<String, Object> vars, JobContext ctx) {
        try {
            String caseId = Values.first(vars, null, "caseId");
            String reason = Values.first(vars, null, "escalationReason");
            String decision = Values.first(vars, null, "escalationDecision");
            if (caseId != null) {
                OffsetDateTime now = OffsetDateTime.now();
                Optional<ClinicLetter> found = letters.findByCaseId(caseId);
                if (found.isPresent()) {
                    ClinicLetter l = found.get();
                    l.setEscalationReason(Values.cut(reason, 1000));
                    l.setEscalationDecision(Values.cut(decision, 64));
                    l.setEscalatedAt(now);
                    l.setUpdatedAt(now);
                    letters.save(l);
                }
            }
            appendAudit(ctx, "escalate-to-coordinator", businessKey(vars, ctx, "caseId"),
                    "reason=" + reason + " decision=" + decision);
        } catch (RuntimeException e) {
            warn("recordEscalation", e);
        }
    }

    /** P8：咨询 triage / 路由 upsert（enquiryId 唯一）。 */
    public String recordEnquiry(Map<String, Object> vars, String status, JobContext ctx) {
        try {
            String enquiryId = businessKey(vars, ctx, "enquiryId");
            if (enquiryId == null) {
                return null;
            }
            OffsetDateTime now = OffsetDateTime.now();
            Enquiry e = enquiries.findByEnquiryId(enquiryId).orElseGet(Enquiry::new);
            if (e.getId() == null) {
                e.setEnquiryId(enquiryId);
                e.setCreatedAt(now);
                e.setStatus("TRIAGED");
            }
            e.setPatientName(Values.cut(Values.first(vars, e.getPatientName(), "patientName"), 200));
            e.setInquiryType(Values.cut(Values.first(vars, e.getInquiryType(), "inquiryType",
                    Values.nested(vars, "enquiryForm", "inquiryType", null)), 64));
            e.setContent(Values.cut(Values.first(vars, e.getContent(), "content",
                    Values.nested(vars, "enquiryForm", "content", null)), 4000));
            e.setUrgency(Values.cut(Values.first(vars, e.getUrgency(), "triageUrgency", "urgency"), 32));
            e.setTriageNote(Values.cut(Values.first(vars, e.getTriageNote(), "triageNote"), 1000));
            e.setUrgentKeywords(Values.cut(Values.first(vars, e.getUrgentKeywords(), "urgentKeywords",
                    Values.nested(vars, "enquiryForm", "urgentKeywords", null)), 1000));
            if (status != null) {
                e.setStatus(Values.cut(status, 64));
            } else if (e.getStatus() == null) {
                e.setStatus("TRIAGED");
            }
            e.setUpdatedAt(now);
            Enquiry saved = enquiries.save(e);

            appendAudit(ctx, "record-enquiry", enquiryId,
                    "type=" + saved.getInquiryType() + " urgency=" + saved.getUrgency() + " status=" + saved.getStatus());
            return saved.getEnquiryId();
        } catch (RuntimeException e) {
            warn("recordEnquiry", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 审计

    /** 写一行流程审计（同 processInstanceKey + jobKey + action 幂等）。 */
    public void audit(JobContext ctx, String action, String businessKey, String details) {
        try {
            appendAudit(ctx, action, businessKey, details);
        } catch (RuntimeException e) {
            warn("audit(" + action + ")", e);
        }
    }

    /** 记录一个「本步暂不改业务表」的作业动作（例如仅投递消息的 Worker）。 */
    public void auditOnly(JobContext ctx, String action, String businessKey, String details) {
        audit(ctx, action, businessKey, details);
    }

    // ------------------------------------------------------------------ 统计 / 查询（演示 API 用）

    /** 每张表的行数（演示 API /api/db/summary 使用）。 */
    public Map<String, Long> tableCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("referral", referrals.count());
        m.put("patient", patients.count());
        m.put("appointment", appointments.count());
        m.put("clinical_decision", decisions.count());
        m.put("clinic_letter", letters.count());
        m.put("funding_case", fundingCases.count());
        m.put("payment", payments.count());
        m.put("enquiry", enquiries.count());
        m.put("process_audit", audits.count());
        return m;
    }

    public List<Referral> latestReferrals() {
        return referrals.findTop100ByOrderByIdDesc();
    }

    public List<Payment> latestPayments() {
        return payments.findTop100ByOrderByIdDesc();
    }

    public List<ProcessAudit> latestAudit() {
        return audits.findTop200ByOrderByIdDesc();
    }

    public List<ProcessAudit> auditTrailFor(String businessKey) {
        return audits.findByBusinessKeyOrderByIdAsc(businessKey);
    }

    public List<Appointment> latestAppointments() {
        return appointments.findTop100ByOrderByIdDesc();
    }

    public List<ClinicalDecision> latestDecisions() {
        return decisions.findTop100ByOrderByIdDesc();
    }

    public List<FundingCase> latestFundingCases() {
        return fundingCases.findTop100ByOrderByIdDesc();
    }

    public List<ClinicLetter> latestLetters() {
        return letters.findTop100ByOrderByIdDesc();
    }

    public List<Enquiry> latestEnquiries() {
        return enquiries.findTop100ByOrderByIdDesc();
    }

    public long auditCount() {
        return audits.count();
    }

    public long paymentCount() {
        return payments.count();
    }

    public long referralCount() {
        return referrals.count();
    }

    // ------------------------------------------------------------------ 私有工具

    private void upsertPatient(String patientName, LocalDate dob, JobContext ctx) {
        if (patientName == null) {
            return;
        }
        try {
            String ref = Values.cut(patientName, 200);
            Patient p = patients.findByPatientRef(ref).orElseGet(Patient::new);
            if (p.getId() == null) {
                p.setPatientRef(ref);
                p.setCreatedAt(OffsetDateTime.now());
            }
            p.setFullName(ref);
            if (dob != null) {
                p.setDateOfBirth(dob);
            }
            patients.save(p);
        } catch (RuntimeException e) {
            warn("upsertPatient", e);
        }
    }

    private void appendAudit(JobContext ctx, String action, String businessKey, String details) {
        JobContext c = ctx == null ? new JobContext(null, null, null, null, null, null, "hpas-workers") : ctx;
        String act = Values.cut(action, 128);
        String key = Values.cut(businessKey, 128);
        String det = Values.cut(details, 2000);

        ProcessAudit a = null;
        if (c.processInstanceKey() != null && c.jobKey() != null && act != null) {
            a = audits.findFirstByProcessInstanceKeyAndJobKeyAndAction(
                    c.processInstanceKey(), c.jobKey(), act).orElse(null);
        }
        if (a == null) {
            a = new ProcessAudit();
            a.setProcessInstanceKey(c.processInstanceKey());
            a.setProcessDefinitionId(c.processDefinitionId());
            a.setProcessDefinitionKey(c.processDefinitionKey());
            a.setElementId(c.elementId());
            a.setJobType(c.jobType());
            a.setJobKey(c.jobKey());
        }
        a.setPerformedBy(c.worker() == null ? "hpas-workers" : c.worker());
        a.setPerformedAt(OffsetDateTime.now());
        a.setAction(act);
        a.setBusinessKey(key);
        a.setDetails(det);
        audits.save(a);
    }

    /** 业务键：变量优先，其次用流程实例键派生，保证总有值可用。 */
    private String businessKey(Map<String, Object> vars, JobContext ctx, String... keys) {
        String v = Values.first(vars, null, keys);
        if (v != null) {
            return Values.cut(v, 128);
        }
        if (ctx != null && ctx.processInstanceKey() != null) {
            return "PI-" + ctx.processInstanceKey();
        }
        return null;
    }

    private static String joinMissing(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(String.valueOf(item).trim());
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "[]".equals(s) || "null".equalsIgnoreCase(s)) {
            return null;
        }
        return s;
    }

    private static String normalizePaymentStatus(String paymentStatus, String retryOutcome) {
        if (paymentStatus != null) {
            String s = paymentStatus.trim().toLowerCase();
            if (s.contains("success") || "paid".equals(s) || "succeeded".equals(s)) {
                return STATUS_SUCCESS;
            }
            if (s.contains("duplicate")) {
                return STATUS_DUPLICATE;
            }
            if (s.contains("cancel")) {
                return STATUS_CANCELLED;
            }
            if (s.contains("fail") || s.contains("reject") || s.contains("declin")) {
                return STATUS_FAILED;
            }
            return Values.cut(s, 32);
        }
        if (retryOutcome != null && retryOutcome.toLowerCase().contains("cancel")) {
            return STATUS_CANCELLED;
        }
        return "unknown";
    }

    private static BigDecimal parseAmount(String s) {
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() < 8) {
            return null;
        }
        try {
            return LocalDateTime.parse(t.replace(' ', 'T'));
        } catch (RuntimeException ignore) {
            // 退化为只解析日期
        }
        try {
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t).atStartOfDay();
        } catch (RuntimeException ignore) {
            // 尝试带时区的 ISO 写法
        }
        try {
            return OffsetDateTime.parse(t).toLocalDateTime();
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    private static void warn(String op, RuntimeException e) {
        LOG.warn("PersistenceService.{} failed (process continues; job result unaffected): {}", op, e.toString());
    }
}
