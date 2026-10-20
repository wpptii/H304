package hpas;

import hpas.db.JobContext;
import hpas.db.PersistenceService;
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
 *
 * <p>数据库：信函正文 / 分发时间 / 是否 7 天内完成写入 clinic_letter 表（caseId 唯一）。
 */
@Component
public class LetterWorkers {

    /** BR-27：信函分发的 7 天时限目标。 */
    private static final int DEADLINE_DAYS = 7;

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public LetterWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
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

        Map<String, Object> result = Support.vars(
                "letterDispatched", true,
                "dispatchedAt", Support.stamp(),
                "elapsedDays", elapsedDays,
                "dispatchedWithin7Days", withinDeadline);

        // --- 数据库持久化：信函分发（含 BR-27 的 7 天结论） ---
        Map<String, Object> merged = new java.util.LinkedHashMap<>(vars);
        merged.putAll(result);
        db.recordLetter(merged, JobContext.of(job, "letter-dispatch"));
        // --- 持久化结束 ---

        return result;
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

            // --- 数据库持久化：升级到协调员（clinic_letter 升级字段 + 审计） ---
            JobContext ctx = JobContext.of(job, "notify-pathway-coordinator");
            db.recordEscalation(vars, ctx);
            // --- 持久化结束 ---

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
