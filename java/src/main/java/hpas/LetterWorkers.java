package hpas;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 选择 5：P10 门诊信函编制、批准与分发
 * 对应模型 p10-letter-process.bpmn（含 7 天时限与升级）。
 */
@Component
public class LetterWorkers {

    /** BR-27：信函分发的 7 天时限目标。 */
    private static final int DEADLINE_DAYS = 7;

    private final CamundaClient camundaClient;

    public LetterWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /** 分发信函，并判定是否在 7 天内完成（结果写回 dispatchedWithin7Days 供网关判断）。 */
    @JobWorker(type = "letter-dispatch")
    public Map<String, Object> letterDispatch(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();

        LocalDate intake = Support.parseDate(Support.str(vars, "intakeDate", null));
        LocalDate completed = Support.parseDate(Support.str(vars, "draftCompletedAt", null));
        if (completed == null) {
            completed = LocalDate.now();
        }
        Long elapsedDays = intake == null ? null : ChronoUnit.DAYS.between(intake, completed);
        boolean withinDeadline = elapsedDays == null || elapsedDays <= DEADLINE_DAYS;

        Support.callExternalSystem("letter-dispatch-service", Support.vars(
                "caseId", vars.get("caseId"),
                "letterType", vars.get("letterType"),
                "addressee", vars.get("addressee"),
                "elapsedDays", elapsedDays));

        return Support.vars(
                "letterDispatched", true,
                "dispatchedAt", Support.stamp(),
                "elapsedDays", elapsedDays,
                "dispatchedWithin7Days", withinDeadline);
    }

    /**
     * Message 结束事件「已升级并通知协调员」的 Worker。
     *
     * <p>投递 EscalationRaised，启动外部 Pool（通信服务 / 管理层）的流程实例。
     */
    @JobWorker(type = "notify-pathway-coordinator", autoComplete = false)
    public void notifyPathwayCoordinator(final io.camunda.client.api.worker.JobClient jobClient,
                                         final ActivatedJob job) {
        try {
            Map<String, Object> vars = job.getVariablesAsMap();
            String caseId = Support.str(vars, "caseId", "");

            camundaClient.newPublishMessageCommand()
                    .messageName("EscalationRaised")
                    .correlationKey(caseId)
                    .variables(vars)
                    .timeToLive(java.time.Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(vars)
                    .send()
                    .join();

            Support.log("notify-pathway-coordinator", Support.vars("published", "EscalationRaised", "caseId", caseId));
        } catch (Exception e) {
            Support.log("notify-pathway-coordinator", Support.vars("error", String.valueOf(e.getMessage())));
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
