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
 * 外部 Pool：支付服务提供商 PSP（对应模型 p12-payment-processing.bpmn 的第二个 participant）。
 *
 * <p>由医院抛出的 PaymentRequested 消息启动本 Pool 实例；
 * 处理完成后把 PaymentStatusReceived 回传给医院（关联键 = caseId）。
 */
@Component
public class PspPoolWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(PspPoolWorkers.class);

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public PspPoolWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
    }

    /**
     * PSP 侧处理支付。
     * SIMULATE_PAYMENT=success|failed|duplicate 控制结果，用于演示三条分支（BR-19）。
     */
    @JobWorker(type = "psp-process-payment")
    public Map<String, Object> processPayment(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String scenario = Support.flag("SIMULATE_PAYMENT", "success");
        String caseId = Support.str(vars, "caseId", "CASE");

        Support.callExternalSystem("psp-payment-request", Support.vars(
                "caseId", caseId,
                "amount", vars.get("amount"),
                "paymentMethod", vars.get("paymentMethod"),
                "cardLast4", vars.get("cardLast4"),          // 只传后四位，完整卡号从不进入流程（BR-16）
                "paymentTokenRef", vars.get("paymentTokenRef")));

        switch (scenario) {
            case "failed": {
                Map<String, Object> failed = Support.vars(
                        "paymentStatus", "failed",
                        "failureReason", "Simulation: card issuer declined",
                        "pspReference", null);
                recordPspOutcome(job, vars, failed);
                return failed;
            }
            case "duplicate": {
                Map<String, Object> duplicate = Support.vars(
                        "paymentStatus", "duplicate",
                        "failureReason", "Simulation: a successful charge already exists for this payment",
                        "pspReference", "PSP-DUP-" + System.currentTimeMillis());
                recordPspOutcome(job, vars, duplicate);
                return duplicate;
            }
            default: {
                Map<String, Object> success = Support.vars(
                        "paymentStatus", "success",
                        "pspReference", "PSP-" + caseId + "-" + System.currentTimeMillis());
                recordPspOutcome(job, vars, success);
                return success;
            }
        }
    }

    /** 把 PSP 的判定结果落库：成功 / 失败 / 重复都留痕，成功支付受「一案一成功」约束。 */
    private void recordPspOutcome(ActivatedJob job, Map<String, Object> vars, Map<String, Object> outcome) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(vars);
        merged.putAll(outcome);
        String result = db.recordPayment(merged, JobContext.of(job, "psp-process-payment"));
        Support.log("psp-process-payment-persistence", Support.vars(
                "caseId", merged.get("caseId"),
                "paymentStatus", merged.get("paymentStatus"),
                "pspReference", merged.get("pspReference"),
                "dbOutcome", result));
    }

    /** 消息结束事件「回传支付状态」的 Worker。 */
    @JobWorker(type = "psp-send-status", autoComplete = false)
    public void sendStatus(final io.camunda.client.api.worker.JobClient jobClient,
                           final ActivatedJob job) {
        try {
            Map<String, Object> vars = job.getVariablesAsMap();
            String caseId = Support.str(vars, "caseId", "");

            // --- 数据库持久化：回传 PSP 状态前落库（幂等键 = caseId|pspReference|status） ---
            db.recordPayment(vars, JobContext.of(job, "psp-send-status"));
            // --- 持久化结束 ---

            camundaClient.newPublishMessageCommand()
                    .messageName("PaymentStatusReceived")
                    .correlationKey(caseId)
                    .variables(vars)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(vars)
                    .send()
                    .join();

            LOG.info("psp-send-status published PaymentStatusReceived for caseId={} status={}",
                    caseId, vars.get("paymentStatus"));
        } catch (Exception e) {
            LOG.error("Could not publish payment status", e);
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
