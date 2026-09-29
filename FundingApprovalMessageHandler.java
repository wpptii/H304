package io.camunda.demo.fundingdetermination;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 资助审批消息处理（对应 BPMN 服务任务 Task_RequestApproval）。
 *
 * Job type = request-funding-approval（与 BPMN zeebe:taskDefinition 一致），
 * 且必须 autoComplete = false —— 因为要在投递消息成功后再手动 complete 当前 Job。
 *
 * 参照 Messgae Example 的 MessageHandeller：
 *   1. 读取流程变量（BPMN IO Mapping 已把 appointmentId / fundingSource /
 *      authorizationRef / approvedAmount 映射为本地变量）；
 *   2. 用 CamundaClient.newPublishMessageCommand() 投递消息 FundingApprovalRequested，
 *      关联键 correlationKey = appointmentId（资助机构据此回执）；
 *   3. 投递成功后 newCompleteCommand(job.getKey()) 完成任务；
 *   4. 任何异常都走 newFailCommand 扣减 retries。
 *
 * 审批回执 FundingApprovalResult 由中间捕获事件 Catch_ApprovalResult 通过
 * 关联键 = appointmentId 自动关联，无需 Java 代码监听——引擎负责订阅。
 */
@Component
public class FundingApprovalMessageHandler {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(FundingApprovalMessageHandler.class);

    private final CamundaClient camundaClient;

    public FundingApprovalMessageHandler(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /** 投递「请求资助批准」消息（FundingApprovalRequested）。 */
    @JobWorker(type = "request-funding-approval", autoComplete = false)
    public void requestFundingApproval(final JobClient jobClient, final ActivatedJob job) {
        try {
            // 1. 读取流程变量
            Map<String, Object> variables = job.getVariablesAsMap();
            String appointmentId = variables.get("appointmentId") == null
                    ? null
                    : variables.get("appointmentId").toString();

            LOG.info("投递消息 FundingApprovalRequested，appointmentId={}", appointmentId);

            // 2. 发布 BPMN 消息：messageName 必须与 BPMN 中消息名称一致，
            //    correlationKey 使用预约号，资助机构按同一关联键回执审批结果。
            camundaClient.newPublishMessageCommand()
                    .messageName("FundingApprovalRequested")
                    .correlationKey(appointmentId)
                    .variables(variables)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();

            // 3. 完成该 Job（投递成功后才完成）
            camundaClient.newCompleteCommand(job.getKey())
                    .variables(variables)
                    .send()
                    .join();

        } catch (Exception e) {
            e.printStackTrace();
            jobClient
                    .newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message FundingApprovalRequested: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
