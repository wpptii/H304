package hpas;

import hpas.db.JobContext;
import hpas.db.PersistenceService;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 选择 3：P3 新患者预约排程
 * 对应模型 p3-appointment-scheduling.bpmn（含 Timer 重试环）。
 *
 * <p>数据库：号源与预约时间写入 appointment 表（appointmentId 唯一）。
 */
@Component
public class SchedulingWorkers {

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public SchedulingWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
    }

    /**
     * Message 抛出事件「调用排程服务」的 Worker（医院 Pool）。
     *
     * <p>投递 SchedulingRequested，启动外部 Pool（排程服务）的流程实例；
     * 排程结果由排程服务通过 SchedulingResult 回传（关联键 = referralId）。
     */
    @JobWorker(type = "send-scheduling-request", autoComplete = false)
    public void sendSchedulingRequest(final io.camunda.client.api.worker.JobClient jobClient,
                                      final ActivatedJob job) {
        try {
            Map<String, Object> vars = job.getVariablesAsMap();
            String referralId = Support.str(vars, "referralId", "");

            // --- 数据库持久化：排程请求（预约行 + 审计） ---
            JobContext ctx = JobContext.of(job, "send-scheduling-request");
            db.upsertAppointment(vars, ctx);
            // --- 持久化结束 ---

            camundaClient.newPublishMessageCommand()
                    .messageName("SchedulingRequested")
                    .correlationKey(referralId)
                    .variables(vars)
                    .timeToLive(java.time.Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(vars)
                    .send()
                    .join();

            Support.log("send-scheduling-request", Support.vars("published", "SchedulingRequested", "referralId", referralId));
        } catch (Exception e) {
            Support.log("send-scheduling-request", Support.vars("error", String.valueOf(e.getMessage())));
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }

    /** Message：通知患者通知团队 / 通信服务。 */
    @JobWorker(type = "notify-notification-team")
    public Map<String, Object> notifyNotificationTeam(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.publish(camundaClient, "BookingConfirmed",
                Support.str(vars, "referralId", ""),
                Support.vars(
                        "referralId", vars.get("referralId"),
                        "slotReference", vars.get("slotReference"),
                        "appointmentDateTime", vars.get("appointmentDateTime"),
                        "location", vars.get("location")));

        // --- 数据库持久化：确认预约（appointmentDateTime -> confirmedAt） ---
        db.upsertAppointment(vars, JobContext.of(job, "notify-notification-team"));
        // --- 持久化结束 ---

        return Support.vars("notificationTeamNotified", true, "notifiedAt", Support.stamp());
    }
}
