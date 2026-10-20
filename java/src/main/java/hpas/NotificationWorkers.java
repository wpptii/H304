package hpas;

import hpas.db.JobContext;
import hpas.db.PersistenceService;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 选择 4：P4 患者通知与联系
 * 对应模型 p4-patient-notification.bpmn（含「否：重试」分支）。
 *
 * <p>数据库：通知渠道与尝试次数写 appointment，联系结果写 process_audit。
 */
@Component
public class NotificationWorkers {

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public NotificationWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
    }

    /**
     * 发送通知（BR-11）。
     * SIMULATE_NOTIFY_FAIL=1 → 第一次尝试返回 notifyStatus=false，走 BPMN 的重试分支。
     */
    @JobWorker(type = "communication-send")
    public Map<String, Object> communicationSend(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        int attempt = Support.num(vars, "notifyAttempts") + 1;
        boolean forcedFail = "1".equals(Support.flag("SIMULATE_NOTIFY_FAIL", "0")) && attempt == 1;
        String method = Support.str(vars, "method", "");
        String channel = Support.CHANNEL.getOrDefault(method, method);

        Map<String, Object> result;
        if (forcedFail) {
            result = Support.vars(
                    "notifyStatus", false,
                    "notifyAttempts", attempt,
                    "notifyError", "Simulated send failure: external communication service timed out");
        } else {
            Support.callExternalSystem("communication-service", Support.vars(
                    "channel", channel,
                    "to", vars.get("contactValue"),
                    "language", Support.str(vars, "language", "zh-CN"),
                    "appointmentId", vars.get("appointmentId")));

            result = Support.vars(
                    "notifyStatus", true,
                    "notifyAttempts", attempt,
                    "notifyChannel", channel);
        }

        // --- 数据库持久化：通知尝试次数 / 渠道写 appointment，并留审计 ---
        Map<String, Object> merged = new java.util.LinkedHashMap<>(vars);
        merged.putAll(result);
        JobContext ctx = JobContext.of(job, "communication-send");
        db.upsertAppointment(merged, ctx);
        db.audit(ctx, "communication-send", Support.str(merged, "appointmentId",
                        Support.str(merged, "referralId", null)),
                "attempt=" + attempt + " notifyStatus=" + result.get("notifyStatus")
                        + " channel=" + (result.get("notifyChannel") == null ? channel : result.get("notifyChannel"))
                        + " error=" + result.get("notifyError"));
        // --- 持久化结束 ---

        return result;
    }

    /** 记录联系尝试（BR-12）。 */
    @JobWorker(type = "contact-log")
    public Map<String, Object> contactLog(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String contactLogId = "LOG-" + Support.str(vars, "appointmentId", "NA") + "-" + System.currentTimeMillis();
        String result = Support.str(vars, "contactResult", "");

        Support.callExternalSystem("patient-contact-log", Support.vars(
                "id", contactLogId,
                "appointmentId", vars.get("appointmentId"),
                "result", result,
                "phoneNumber", vars.get("phoneNumber"),
                "interpreterUsed", vars.get("interpreterUsed"),
                "remarks", vars.get("remarks")));

        // --- 数据库持久化：联系尝试记录（appointment 状态 + 审计） ---
        JobContext ctx = JobContext.of(job, "contact-log");
        db.upsertAppointment(vars, ctx);
        db.audit(ctx, "contact-log", Support.str(vars, "appointmentId", null),
                "contactLogId=" + contactLogId + " result=" + result
                        + " interpreterUsed=" + vars.get("interpreterUsed"));
        // --- 持久化结束 ---

        return Support.vars(
                "contactLogId", contactLogId,
                "contactLogged", true,
                "contactResult", result,
                "rescheduleRequested", "reschedule".equals(result),
                "contactLoggedAt", Support.stamp());
    }

    /**
     * Message 结束事件「联系完成 / 已通知团队」的 Worker。
     *
     * <p>投递 PatientContactRecorded，启动外部 Pool（通信服务 / 患者）的流程实例。
     */
    @JobWorker(type = "notify-confirm", autoComplete = false)
    public void notifyConfirm(final io.camunda.client.api.worker.JobClient jobClient,
                              final ActivatedJob job) {
        try {
            Map<String, Object> vars = job.getVariablesAsMap();
            String appointmentId = Support.str(vars, "appointmentId", "");

            // --- 数据库持久化：预约已通知患者（状态 NOTIFIED） ---
            JobContext ctx = JobContext.of(job, "notify-confirm");
            db.upsertAppointment(vars, ctx);
            // --- 持久化结束 ---

            camundaClient.newPublishMessageCommand()
                    .messageName("PatientContactRecorded")
                    .correlationKey(appointmentId)
                    .variables(vars)
                    .timeToLive(java.time.Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(vars)
                    .send()
                    .join();

            Support.log("notify-confirm", Support.vars("published", "PatientContactRecorded", "appointmentId", appointmentId));
        } catch (Exception e) {
            Support.log("notify-confirm", Support.vars("error", String.valueOf(e.getMessage())));
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
