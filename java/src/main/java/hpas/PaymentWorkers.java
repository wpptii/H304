package hpas;

import hpas.db.JobContext;
import hpas.db.PersistenceService;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 选择 7：P12 支付处理
 * 对应模型 p12-payment-processing.bpmn（成功 / 失败 / 重复三条分支）。
 *
 * <p>数据库：payment 表用 psp_reference 唯一 + 「同一 caseId 只能有一笔成功支付」的约束
 * 做重复扣款防护（{@link PersistenceService#recordPayment}）。
 */
@Component
public class PaymentWorkers {

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public PaymentWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
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

            // --- 数据库持久化：发起支付前先检查是否已有成功支付（重复扣款防护） ---
            JobContext ctx = JobContext.of(job, "send-payment-request");
            boolean alreadyPaid = db.hasSuccessfulPayment(caseId);
            db.audit(ctx, "send-payment-request", caseId,
                    "amount=" + vars.get("amount") + " method=" + vars.get("paymentMethod")
                            + " alreadyHasSuccessfulPayment=" + alreadyPaid);
            // --- 持久化结束 ---

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

        Map<String, Object> result = Support.vars(
                "paymentRetryOutcome", "cancel".equals(action) ? "cancelled" : "retried",
                "retriedAt", Support.stamp());

        // --- 数据库持久化：重试 / 取消（cancelled 会作为一条痕迹写入 payment 表） ---
        Map<String, Object> merged = new java.util.LinkedHashMap<>(vars);
        merged.putAll(result);
        db.recordPayment(merged, JobContext.of(job, "payment-retry"));
        // --- 持久化结束 ---

        return result;
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

        // --- 数据库持久化：最终支付结论（重复成功会被拦住并留痕） ---
        String outcome = db.recordPayment(vars, JobContext.of(job, "notify-care-team"));
        Support.log("notify-care-team-persistence", Support.vars(
                "caseId", vars.get("caseId"), "paymentStatus", vars.get("paymentStatus"),
                "dbOutcome", outcome));
        // --- 持久化结束 ---

        return Support.vars("careTeamNotified", true, "careTeamNotifiedAt", Support.stamp());
    }
}
