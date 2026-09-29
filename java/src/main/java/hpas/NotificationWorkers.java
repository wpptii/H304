package hpas;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 选择 4：P4 患者通知与联系
 * 对应模型 p4-patient-notification.bpmn（含「否：重试」分支）。
 */
@Component
public class NotificationWorkers {

    private final CamundaClient camundaClient;

    public NotificationWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
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

        if (forcedFail) {
            return Support.vars(
                    "notifyStatus", false,
                    "notifyAttempts", attempt,
                    "notifyError", "模拟发送失败：外部通信服务超时");
        }

        Support.callExternalSystem("communication-service", Support.vars(
                "channel", channel,
                "to", vars.get("contactValue"),
                "language", Support.str(vars, "language", "zh-CN"),
                "appointmentId", vars.get("appointmentId")));

        return Support.vars(
                "notifyStatus", true,
                "notifyAttempts", attempt,
                "notifyChannel", channel);
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
