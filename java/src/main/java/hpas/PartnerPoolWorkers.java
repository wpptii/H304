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
 * 外部 Pool：转诊机构 / 预约团队 / 其他医院（对应模型 p2-clinical-review.bpmn 的第二个 participant）。
 *
 * <p>由医院抛出的四种决定通知分别启动本 Pool 的实例；
 * 其中「要求补件」分支在外部补齐材料后，把 SupplementReceived 回传给医院（关联键 = referralId）。
 */
@Component
public class PartnerPoolWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(PartnerPoolWorkers.class);

    private final CamundaClient camundaClient;

    public PartnerPoolWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /** 消息结束事件「回传补充材料」的 Worker。 */
    @JobWorker(type = "partner-send-supplement", autoComplete = false)
    public void sendSupplement(final io.camunda.client.api.worker.JobClient jobClient,
                               final ActivatedJob job) {
        try {
            Map<String, Object> vars = job.getVariablesAsMap();
            String referralId = Support.str(vars, "referralId", "");

            String provided = Support.nestedStr(vars, "partnerSupplementForm", "providedItems", "补充材料");
            vars.put("supplementProvided", provided);

            camundaClient.newPublishMessageCommand()
                    .messageName("SupplementReceived")
                    .correlationKey(referralId)
                    .variables(vars)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(vars)
                    .send()
                    .join();

            LOG.info("partner-send-supplement published SupplementReceived for referralId={}", referralId);
        } catch (Exception e) {
            LOG.error("Could not publish SupplementReceived", e);
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
