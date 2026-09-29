package io.camunda.demo.letter_process;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * External Worker for the "notify-pathway-coordinator" Service Task.
 *
 * <p>流程：通知路径协调员 —— 向协调员投递跨 Pool 的 BPMN 消息 {@code EscalationRaised}
 * （消息名称取任务头 header {@code messageName}，缺省为 EscalationRaised），随后完成该
 * Service Task。对应 Message Example 中 {@code MessageHandeller} 的消息发布写法。
 *
 * <p>因为要手动发布消息后再 complete，所以设置 {@code autoComplete = false}，由本类在
 * 消息发布成功后显式 complete。
 */
@Component
public class NotifyCoordinatorWorker {

    private final static Logger LOG = LoggerFactory.getLogger(NotifyCoordinatorWorker.class);

    /** 消息默认名称，若任务头未提供 messageName 则使用。 */
    private static final String DEFAULT_MESSAGE_NAME = "EscalationRaised";

    // Camunda Client，用于发布消息
    private final CamundaClient camundaClient;

    public NotifyCoordinatorWorker(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    @JobWorker(type = "notify-pathway-coordinator", autoComplete = false)
    public void notifyCoordinator(final JobClient jobClient, final ActivatedJob job) {
        try {
            // 读取流程变量（ioMapping 已把 escalationForm.* 映射为 escalationReason / escalationDecision）
            Map<String, Object> variables = job.getVariablesAsMap();
            String caseId = String.valueOf(variables.getOrDefault("caseId", ""));
            String escalationReason = String.valueOf(variables.getOrDefault("escalationReason", ""));
            String escalationDecision = String.valueOf(variables.getOrDefault("escalationDecision", ""));

            // 消息名称优先取任务头，缺省为 BPMN 消息名 EscalationRaised
            String messageName = job.getCustomHeaders().getOrDefault("messageName", DEFAULT_MESSAGE_NAME);

            LOG.info("Publishing {} for case {} (reason: {}, decision: {})",
                    messageName, caseId, escalationReason, escalationDecision);

            // 1) 发布 BPMN 消息（跨 Pool 通知协调员），以 caseId 作为关联键
            camundaClient.newPublishMessageCommand()
                    .messageName(messageName)
                    .correlationKey(caseId)
                    .variables(variables)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();

            // 2) 完成该 Service Task
            camundaClient.newCompleteCommand(job.getKey())
                    .variables(variables)
                    .send()
                    .join();

            LOG.info("notify-pathway-coordinator job completed: {}", job.getKey());
        } catch (Exception e) {
            e.printStackTrace();
            jobClient
                    .newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish escalation message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
