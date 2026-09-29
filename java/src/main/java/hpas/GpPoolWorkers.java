package hpas;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 外部 Pool：GP / 其他医院（对应模型 p1-referral-intake.bpmn 的第二个 participant）。
 *
 * <p>这是「真双 Pool」结构下的外部方流程：
 * 由医院抛出的 ReferralSupplementRequested 消息启动本 Pool 的实例，
 * 外部人员完成「补充材料」用户任务后，由本 Worker 把材料回传给医院。
 */
@Component
public class GpPoolWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(GpPoolWorkers.class);

    private final CamundaClient camundaClient;

    public GpPoolWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /**
     * 消息结束事件「回传补充材料」的 Worker（与范例 send-message-02 的写法一致）。
     *
     * <p>投递 ReferralSupplementReceived，关联键 = 医院侧捕获事件的
     * {@code zeebe:subscription correlationKey="=referralId"}。
     */
    @JobWorker(type = "gp-send-supplement", autoComplete = false)
    public void sendSupplement(final io.camunda.client.api.worker.JobClient jobClient,
                               final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();
            String referralId = Support.str(variables, "referralId", "");

            // 把「已补充的材料」带回医院侧：医院复查时会据此判定材料齐全，补件闭环结束
            String providedItems = Support.nestedStr(variables, "supplementForm", "providedItems", "");
            if (providedItems.isBlank()) {
                providedItems = Support.str(variables, "providedItems", "补充材料");
            }
            variables.put("supplementProvided", providedItems);

            camundaClient.newPublishMessageCommand()
                    .messageName("ReferralSupplementReceived")
                    .correlationKey(referralId)
                    .variables(variables)
                    .timeToLive(java.time.Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(variables)
                    .send()
                    .join();

            LOG.info("gp-send-supplement published ReferralSupplementReceived for referralId={}", referralId);
        } catch (Exception e) {
            LOG.error("Could not publish supplement reply", e);
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
