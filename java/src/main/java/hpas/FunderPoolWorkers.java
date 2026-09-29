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
 * 外部 Pool：资助机构 / 保险公司（对应模型 p7-funding-determination.bpmn 的第二个 participant）。
 *
 * <p>由医院抛出的 FundingApprovalRequested 消息启动本 Pool 实例；
 * 审批完成后，由本 Worker 把 FundingApprovalResult 回传给医院（关联键 = appointmentId）。
 */
@Component
public class FunderPoolWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(FunderPoolWorkers.class);

    private final CamundaClient camundaClient;

    public FunderPoolWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /** 消息结束事件「回传批准结果」的 Worker。 */
    @JobWorker(type = "funder-send-result", autoComplete = false)
    public void sendResult(final io.camunda.client.api.worker.JobClient jobClient,
                           final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();
            String appointmentId = Support.str(variables, "appointmentId", "");

            // 审批结论随消息回传医院：医院侧「记录批准」表单会直接看到这些字段
            variables.put("funderReplyReceived", true);

            camundaClient.newPublishMessageCommand()
                    .messageName("FundingApprovalResult")
                    .correlationKey(appointmentId)
                    .variables(variables)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(variables)
                    .send()
                    .join();

            LOG.info("funder-send-result published FundingApprovalResult for appointmentId={}", appointmentId);
        } catch (Exception e) {
            LOG.error("Could not publish funding approval result", e);
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
