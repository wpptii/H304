package hpas;

import hpas.db.JobContext;
import hpas.db.PersistenceService;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 外部 Pool：排程服务（对应模型 p3-appointment-scheduling.bpmn 的第二个 participant）。
 *
 * <p>由医院抛出的 SchedulingRequested 消息启动本 Pool 实例；
 * 分配号源后把 SchedulingResult 回传给医院（关联键 = referralId）。
 */
@Component
public class SchedulingPoolWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulingPoolWorkers.class);

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public SchedulingPoolWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
    }

    /**
     * 排程服务分配号源。
     * SIMULATE_SCHEDULING_FAIL_TIMES=N 让前 N 次请求失败，用于演示 Timer 重试环（BR-13）。
     */
    @JobWorker(type = "scheduler-process-request")
    public Map<String, Object> processRequest(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String referralId = Support.str(vars, "referralId", "REF");

        int attempt = (int) Support.num(vars, "schedulerAttempt") + 1;
        int failTimes = 0;
        try {
            failTimes = Integer.parseInt(Support.flag("SIMULATE_SCHEDULING_FAIL_TIMES", "0").trim());
        } catch (NumberFormatException ignored) {
            failTimes = 0;
        }

        Support.callExternalSystem("external-scheduling-service", Support.vars(
                "referralId", referralId,
                "department", vars.get("department"),
                "priority", vars.get("priority"),
                "preferredWindow", vars.get("preferredWindow"),
                "attempt", attempt));

        if (attempt <= failTimes) {
            Map<String, Object> failed = Support.vars(
                    "schedulerAttempt", attempt,
                    "scheduleStatus", "failed",
                    "failureReason", "Simulation: attempt " + attempt + ": no slots available",
                    "slotReference", null);
            recordAttempt(job, vars, attempt, failed);
            return failed;
        }

        Map<String, Object> success = Support.vars(
                "schedulerAttempt", attempt,
                "scheduleStatus", "success",
                "slotReference", "SLOT-" + referralId + "-" + attempt,
                "failureReason", null);
        recordAttempt(job, vars, attempt, success);
        return success;
    }

    /** 把号源分配结果（成功 / 失败重试）落库：appointment 行 + 审计。 */
    private void recordAttempt(ActivatedJob job, Map<String, Object> vars, int attempt,
                               Map<String, Object> outcome) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(vars);
        merged.putAll(outcome);
        JobContext ctx = JobContext.of(job, "scheduler-process-request");
        db.upsertAppointment(merged, ctx);
        db.audit(ctx, "scheduler-attempt-" + attempt, Support.str(merged, "referralId", null),
                "scheduleStatus=" + outcome.get("scheduleStatus")
                        + " slotReference=" + outcome.get("slotReference")
                        + " failureReason=" + outcome.get("failureReason"));
    }

    /** 消息结束事件「回传排程结果」的 Worker。 */
    @JobWorker(type = "scheduler-send-result", autoComplete = false)
    public void sendResult(final io.camunda.client.api.worker.JobClient jobClient,
                           final ActivatedJob job) {
        try {
            Map<String, Object> vars = job.getVariablesAsMap();
            String referralId = Support.str(vars, "referralId", "");

            // --- 数据库持久化：回传前的最后一次预约状态 ---
            db.upsertAppointment(vars, JobContext.of(job, "scheduler-send-result"));
            // --- 持久化结束 ---

            camundaClient.newPublishMessageCommand()
                    .messageName("SchedulingResult")
                    .correlationKey(referralId)
                    .variables(vars)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(vars)
                    .send()
                    .join();

            LOG.info("scheduler-send-result published SchedulingResult referralId={} status={}",
                    referralId, vars.get("scheduleStatus"));
        } catch (Exception e) {
            LOG.error("Could not publish scheduling result", e);
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
