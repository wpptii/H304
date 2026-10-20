package hpas.api;

import hpas.db.PersistenceService;
import hpas.db.domain.Appointment;
import hpas.db.domain.ClinicLetter;
import hpas.db.domain.ClinicalDecision;
import hpas.db.domain.Enquiry;
import hpas.db.domain.FundingCase;
import hpas.db.domain.Payment;
import hpas.db.domain.ProcessAudit;
import hpas.db.domain.Referral;
import hpas.db.repo.AppUserRepository;
import hpas.db.repo.UserRoleRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读演示 API：把数据库里的持久化结果直接查出来，方便用 curl / Invoke-RestMethod 演示。
 *
 * <p>监听 {@code server.port=8090}（8080 是 Camunda）。
 * 全部是 GET，不会改动业务数据，也不会发消息。
 */
@RestController
@RequestMapping("/api/db")
public class DbDemoController {

    private final PersistenceService db;
    private final AppUserRepository users;
    private final UserRoleRepository roles;

    public DbDemoController(PersistenceService db, AppUserRepository users, UserRoleRepository roles) {
        this.db = db;
        this.users = users;
        this.roles = roles;
    }

    /** 每张表的行数 —— 最直观地证明「数据留在数据库里」。 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> counts = new LinkedHashMap<>(db.tableCounts());
        counts.put("app_user", users.count());
        counts.put("user_role", roles.count());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", "H2 file ./data/hpas (jdbc:h2:file:./data/hpas;AUTO_SERVER=TRUE)");
        out.put("generatedAt", OffsetDateTime.now().toString());
        out.put("tables", counts);
        out.put("totalRows", counts.values().stream().mapToLong(v -> ((Number) v).longValue()).sum());
        return out;
    }

    /** 转诊单 + 临床决定（跨实例 SQL 查询的结果）。 */
    @GetMapping("/referrals")
    public Map<String, Object> referrals() {
        List<Referral> list = db.latestReferrals();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("items", list.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("referralId", r.getReferralId());
            m.put("patientName", r.getPatientName());
            m.put("patientDob", r.getPatientDob());
            m.put("referralSource", r.getReferralSource());
            m.put("receivedDate", r.getReceivedDate());
            m.put("completenessStatus", r.getCompletenessStatus());
            m.put("missingItems", r.getMissingItems());
            m.put("status", r.getStatus());
            m.put("createdAt", r.getCreatedAt());
            m.put("updatedAt", r.getUpdatedAt());
            m.put("latestClinicalDecision", db.latestDecisions().stream()
                    .filter(d -> r.getReferralId() != null && r.getReferralId().equals(d.getReferralId()))
                    .findFirst().map(ClinicalDecision::getDecision).orElse(null));
            return m;
        }).toList());
        return out;
    }

    /** 支付记录 —— 重复扣款是否被拦住，一眼可见。 */
    @GetMapping("/payments")
    public Map<String, Object> payments() {
        List<Payment> list = db.latestPayments();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("successfulCount", list.stream().filter(p -> "success".equals(p.getStatus())).count());
        out.put("items", list.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caseId", p.getCaseId());
            m.put("amount", p.getAmount());
            m.put("currency", p.getCurrency());
            m.put("method", p.getMethod());
            m.put("pspReference", p.getPspReference());
            m.put("status", p.getStatus());
            m.put("idempotencyKey", p.getIdempotencyKey());
            m.put("failureReason", p.getFailureReason());
            m.put("paidAt", p.getPaidAt());
            return m;
        }).toList());
        return out;
    }

    /** 独立审计轨迹（最近 200 行）。 */
    @GetMapping("/audit")
    public Map<String, Object> audit() {
        List<ProcessAudit> list = db.latestAudit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("items", list.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("processInstanceKey", a.getProcessInstanceKey());
            m.put("processDefinitionId", a.getProcessDefinitionId());
            m.put("elementId", a.getElementId());
            m.put("jobType", a.getJobType());
            m.put("jobKey", a.getJobKey());
            m.put("performedBy", a.getPerformedBy());
            m.put("performedAt", a.getPerformedAt());
            m.put("action", a.getAction());
            m.put("businessKey", a.getBusinessKey());
            m.put("details", a.getDetails());
            return m;
        }).toList());
        return out;
    }

    /** 一个业务键（referralId / appointmentId / caseId / enquiryId）的完整轨迹。 */
    @GetMapping("/audit/{businessKey}")
    public Map<String, Object> auditFor(@PathVariable String businessKey) {
        List<ProcessAudit> list = db.auditTrailFor(businessKey);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessKey", businessKey);
        out.put("count", list.size());
        out.put("items", list.stream().map(ProcessAudit::getAction).toList());
        out.put("rows", list);
        return out;
    }

    /** 端到端案例链（P1 转诊 → P2 决定 → P3/P4 预约 → P7 资助 → P10 信函 → P12 支付）。 */
    @GetMapping("/chain/{businessKey}")
    public Map<String, Object> chain(@PathVariable String businessKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessKey", businessKey);
        out.put("referral", db.latestReferrals().stream()
                .filter(r -> businessKey.equals(r.getReferralId())).findFirst().orElse(null));
        out.put("clinicalDecision", db.latestDecisions().stream()
                .filter(d -> businessKey.equals(d.getReferralId())).findFirst().orElse(null));
        out.put("appointments", db.latestAppointments().stream()
                .filter(a -> businessKey.equals(a.getReferralId()) || businessKey.equals(a.getAppointmentId()))
                .toList());
        out.put("funding", db.latestFundingCases().stream()
                .filter(f -> businessKey.equals(f.getCaseId()) || businessKey.equals(f.getAppointmentId())
                        || businessKey.equals(f.getReferralId())).toList());
        out.put("letters", db.latestLetters().stream()
                .filter(l -> businessKey.equals(l.getCaseId())).toList());
        out.put("payments", db.latestPayments().stream()
                .filter(p -> businessKey.equals(p.getCaseId())).toList());
        out.put("auditTrail", db.auditTrailFor(businessKey).stream().map(ProcessAudit::getAction).toList());
        return out;
    }

    /** 其它业务表的概览（预约 / 资助 / 信函 / 咨询）。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appointments", db.latestAppointments().stream().map(Appointment::getAppointmentId).toList());
        out.put("fundingCases", db.latestFundingCases().stream().map(FundingCase::getCaseId).toList());
        out.put("letters", db.latestLetters().stream().map(ClinicLetter::getCaseId).toList());
        out.put("enquiries", db.latestEnquiries().stream().map(Enquiry::getEnquiryId).toList());
        return out;
    }

    /** 预约表 —— 号源 / 确认时间 / 通知渠道与尝试次数（P3 + P4）。 */
    @GetMapping("/appointments")
    public Map<String, Object> appointments() {
        List<Appointment> list = db.latestAppointments();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("items", list.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("appointmentId", a.getAppointmentId());
            m.put("referralId", a.getReferralId());
            m.put("specialty", a.getSpecialty());
            m.put("priority", a.getPriority());
            m.put("slotReference", a.getSlotReference());
            m.put("appointmentDateTime", a.getAppointmentDateTime());
            m.put("status", a.getStatus());
            m.put("confirmedAt", a.getConfirmedAt());
            m.put("notificationChannel", a.getNotificationChannel());
            m.put("notificationAttempts", a.getNotificationAttempts());
            return m;
        }).toList());
        return out;
    }

    /** 临床决定（P2）。 */
    @GetMapping("/decisions")
    public Map<String, Object> decisions() {
        List<ClinicalDecision> list = db.latestDecisions();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("items", list.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("referralId", d.getReferralId());
            m.put("decision", d.getDecision());
            m.put("decidedBy", d.getDecidedBy());
            m.put("reason", d.getReason());
            m.put("decidedAt", d.getDecidedAt());
            return m;
        }).toList());
        return out;
    }

    /** 资助案例（P7）。 */
    @GetMapping("/funding")
    public Map<String, Object> funding() {
        List<FundingCase> list = db.latestFundingCases();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("items", list.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caseId", f.getCaseId());
            m.put("referralId", f.getReferralId());
            m.put("appointmentId", f.getAppointmentId());
            m.put("fundingSource", f.getFundingSource());
            m.put("approvalReference", f.getApprovalReference());
            m.put("approvedAmount", f.getApprovedAmount());
            m.put("status", f.getStatus());
            m.put("approvedAt", f.getApprovedAt());
            m.put("restrictions", f.getRestrictions());
            return m;
        }).toList());
        return out;
    }

    /** 信函（P10）。 */
    @GetMapping("/letters")
    public Map<String, Object> letters() {
        List<ClinicLetter> list = db.latestLetters();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("items", list.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caseId", l.getCaseId());
            m.put("patientName", l.getPatientName());
            m.put("letterType", l.getLetterType());
            m.put("addressee", l.getAddressee());
            m.put("status", l.getStatus());
            m.put("dispatchedAt", l.getDispatchedAt());
            m.put("elapsedDays", l.getElapsedDays());
            m.put("dispatchedWithin7Days", l.getDispatchedWithin7Days());
            m.put("escalationDecision", l.getEscalationDecision());
            return m;
        }).toList());
        return out;
    }

    /** 咨询（P8）。 */
    @GetMapping("/enquiries")
    public Map<String, Object> enquiries() {
        List<Enquiry> list = db.latestEnquiries();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("items", list.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enquiryId", e.getEnquiryId());
            m.put("inquiryType", e.getInquiryType());
            m.put("patientName", e.getPatientName());
            m.put("urgency", e.getUrgency());
            m.put("triageNote", e.getTriageNote());
            m.put("urgentKeywords", e.getUrgentKeywords());
            m.put("status", e.getStatus());
            return m;
        }).toList());
        return out;
    }

    /** 健康检查：数据库连通 + 行数。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            long referrals = db.referralCount();
            out.put("status", "UP");
            out.put("referralRows", referrals);
            out.put("paymentRows", db.paymentCount());
            out.put("auditRows", db.auditCount());
            out.put("checkedAt", OffsetDateTime.now().toString());
        } catch (RuntimeException e) {
            out.put("status", "DOWN");
            out.put("error", e.toString());
        }
        return out;
    }
}
