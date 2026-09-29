package hpas;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 选择 7：P12 支付处理
 * 对应模型 p12-payment-processing.bpmn（成功 / 失败 / 重复三条分支）。
 */
@Component
public class PaymentWorkers {

    private final CamundaClient camundaClient;

    public PaymentWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /**
     * Message 抛出事件「调用 PSP 支付」的 Worker（医院 Pool）。
     *
     * <p>投递 PaymentRequested，启动外部 Pool（PSP）的流程实例；
     * 支付结果由 PSP 侧通过 PaymentStatusReceived 回传（关联键 = caseId）。
     */
    @JobWorker(type = "send-payment-request", autoComplete = false)
    public void sendPaymentRequest(final io.camunda.client.api.worker.JobClient jobClient,
                                   final ActivatedJob job) {
        try {
            Map<String, Object> vars = job.getVariablesAsMap();
            String caseId = Support.str(vars, "caseId", "");

            // 只把卡后四位 / 令牌引用发给 PSP，完整卡号从不进入流程（BR-16）
            vars.put("cardLast4", Support.nestedStr(vars, "paymentForm", "cardLast4", ""));
            vars.put("paymentTokenRef", Support.nestedStr(vars, "paymentForm", "paymentTokenRef", ""));
            vars.put("amount", Support.nestedStr(vars, "paymentForm", "amount", ""));
            vars.put("paymentMethod", Support.nestedStr(vars, "paymentForm", "paymentMethod", ""));

            camundaClient.newPublishMessageCommand()
                    .messageName("PaymentRequested")
                    .correlationKey(caseId)
                    .variables(vars)
                    .timeToLive(java.time.Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(vars)
                    .send()
                    .join();

            Support.log("send-payment-request", Support.vars("published", "PaymentRequested", "caseId", caseId));
        } catch (Exception e) {
            Support.log("send-payment-request", Support.vars("error", String.valueOf(e.getMessage())));
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }

    /** 重试 / 取消支付（BR-17）。 */
    @JobWorker(type = "payment-retry")
    public Map<String, Object> paymentRetry(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String action = "cancel".equals(Support.str(vars, "handling", "retry")) ? "cancel" : "retry";

        Support.callExternalSystem("psp-payment-retry", Support.vars(
                "caseId", vars.get("caseId"),
                "pspReference", vars.get("pspReference"),
                "action", action));

        return Support.vars(
                "paymentRetryOutcome", "cancel".equals(action) ? "cancelled" : "retried",
                "retriedAt", Support.stamp());
    }

    /** Message：通知治疗预约团队（BR-19 只有支付成功才确认预约）。 */
    @JobWorker(type = "notify-care-team")
    public Map<String, Object> notifyCareTeam(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.publish(camundaClient, "CareTeamNotified",
                Support.str(vars, "caseId", ""),
                Support.vars(
                        "caseId", vars.get("caseId"),
                        "pspReference", vars.get("pspReference"),
                        "amount", vars.get("amount")));
        return Support.vars("careTeamNotified", true, "careTeamNotifiedAt", Support.stamp());
    }
}
