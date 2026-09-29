package hpas;

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

    public PspPoolWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
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
            case "failed":
                return Support.vars(
                        "paymentStatus", "failed",
                        "failureReason", "模拟：发卡行拒绝",
                        "pspReference", null);
            case "duplicate":
                return Support.vars(
                        "paymentStatus", "duplicate",
                        "failureReason", "模拟：同一笔费用已有成功扣款",
                        "pspReference", "PSP-DUP-" + System.currentTimeMillis());
            default:
                return Support.vars(
                        "paymentStatus", "success",
                        "pspReference", "PSP-" + caseId + "-" + System.currentTimeMillis());
        }
    }

    /** 消息结束事件「回传支付状态」的 Worker。 */
    @JobWorker(type = "psp-send-status", autoComplete = false)
    public void sendStatus(final io.camunda.client.api.worker.JobClient jobClient,
                           final ActivatedJob job) {
        try {
            Map<String, Object> vars = job.getVariablesAsMap();
            String caseId = Support.str(vars, "caseId", "");

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
