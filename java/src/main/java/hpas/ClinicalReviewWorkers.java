package hpas;

import hpas.db.JobContext;
import hpas.db.PersistenceService;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 选择 2：P2 临床审核与转诊决策
 * 对应模型 p2-clinical-review.bpmn。
 *
 * <p>四个 notify-* / request-supplement 是「消息分支」：由 Worker 投递对应 BPMN 消息，
 * 与范例 MessageHandeller 的做法一致（消息抛出事件挂 taskDefinition，由 Worker publish）。
 *
 * <p>数据库：决定本身写 clinical_decision / referral，每个分支都写 process_audit。
 */
@Component
public class ClinicalReviewWorkers {

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public ClinicalReviewWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
    }

    /** 写审计 / 更新状态（BR-04）。 */
    @JobWorker(type = "clinical-decision-audit")
    public Map<String, Object> clinicalDecisionAudit(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.callExternalSystem("clinical-decision-audit-log", Support.vars(
                "referralId", vars.get("referralId"),
                "decision", vars.get("decision"),
                "reason", vars.get("reason"),
                "decidedBy", vars.get("decidedBy")));

        // --- 数据库持久化：临床决定 + 转诊状态 ---
        db.recordClinicalDecision(vars, JobContext.of(job, "clinical-decision-audit"));
        // --- 持久化结束 ---

        return Support.vars(
                "auditRecorded", true,
                "auditId", "AUD-" + Support.str(vars, "referralId", "NA") + "-" + System.currentTimeMillis(),
                "auditAt", Support.stamp());
    }

    /** 接受 → 通知预约团队 / 排程服务（BR-03 门控：只有本分支会执行）。 */
    @JobWorker(type = "notify-scheduling-team")
    public Map<String, Object> notifySchedulingTeam(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.publish(camundaClient, "ReferralAccepted",
                Support.str(vars, "referralId", ""),
                Support.vars("referralId", vars.get("referralId")));
        auditBranch(job, vars, "notify-scheduling-team", "ReferralAccepted");
        return Support.vars("messageSent", true, "messageName", "ReferralAccepted", "sentAt", Support.stamp());
    }

    /** 拒绝 → 通知转诊机构。 */
    @JobWorker(type = "notify-referral-institution")
    public Map<String, Object> notifyReferralInstitution(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.publish(camundaClient, "ReferralRejected",
                Support.str(vars, "referralId", ""),
                Support.vars("referralId", vars.get("referralId"), "reason", vars.get("reason")));
        auditBranch(job, vars, "notify-referral-institution", "ReferralRejected");
        return Support.vars("messageSent", true, "messageName", "ReferralRejected", "sentAt", Support.stamp());
    }

    /** 要求补充 → 通知 GP。 */
    @JobWorker(type = "request-supplement")
    public Map<String, Object> requestSupplement(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.publish(camundaClient, "SupplementRequested",
                Support.str(vars, "referralId", ""),
                Support.vars("referralId", vars.get("referralId"), "missingItems", vars.get("missingItems")));
        JobContext ctx = JobContext.of(job, "request-supplement");
        db.recordSupplement(vars, "requested-by-clinical-review", ctx);
        return Support.vars("messageSent", true, "messageName", "SupplementRequested", "sentAt", Support.stamp());
    }

    /** 转介 → 通知其他医院。 */
    @JobWorker(type = "notify-other-hospital")
    public Map<String, Object> notifyOtherHospital(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.publish(camundaClient, "ReferredToOtherHospital",
                Support.str(vars, "referralId", ""),
                Support.vars("referralId", vars.get("referralId")));
        auditBranch(job, vars, "notify-other-hospital", "ReferredToOtherHospital");
        return Support.vars("messageSent", true, "messageName", "ReferredToOtherHospital", "sentAt", Support.stamp());
    }

    /** 通知分支的审计：更新转诊状态 + 落一行 process_audit。 */
    private void auditBranch(ActivatedJob job, Map<String, Object> vars, String action, String messageName) {
        JobContext ctx = JobContext.of(job, action);
        db.recordReferral(vars, null, ctx);
        db.audit(ctx, action, Support.str(vars, "referralId", null),
                "published " + messageName + " reason=" + vars.get("reason"));
    }
}
