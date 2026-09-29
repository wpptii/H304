package hpas;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 选择 6：P7 资助确定与记录（医院 Pool：财务团队）
 * 对应模型 p7-funding-determination.bpmn —— 双 Pool 结构，外部方见 {@link FunderPoolWorkers}。
 */
@Component
public class FundingWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(FundingWorkers.class);

    private final CamundaClient camundaClient;

    public FundingWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /** 验证资助（BR-15 支付前置门控；BR-17 判断是否需要审批）。 */
    @JobWorker(type = "funding-validation")
    public Map<String, Object> fundingValidation(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String source = Support.str(vars, "fundingSource", "");
        if (source.isBlank()) {
            source = Support.nestedStr(vars, "fundingForm", "fundingSource", "");
        }
        String authorizationRef = Support.str(vars, "authorizationRef", "");
        if (authorizationRef.isBlank()) {
            authorizationRef = Support.nestedStr(vars, "fundingForm", "authorizationRef", "");
        }

        boolean needsApproval = "insurance".equals(source) || "funding-agency".equals(source);
        boolean hasRef = !authorizationRef.isBlank();

        Support.callExternalSystem("funding-validation-service", Support.vars(
                "appointmentId", vars.get("appointmentId"),
                "fundingSource", source,
                "authorizationRef", authorizationRef,
                "approvedAmount", vars.get("approvedAmount")));

        String note;
        if (!needsApproval) {
            note = source + " 无需外部审批，按自费 / 豁免登记";
        } else if (hasRef) {
            note = "授权参考号 " + authorizationRef + " 已登记，等待"
                    + ("insurance".equals(source) ? "保险公司" : "资助机构") + "审批回执";
        } else {
            note = "缺少授权参考号，仍按需审批处理";
        }

        return Support.vars(
                "fundingValid", needsApproval ? hasRef : true,
                "approvalRequired", needsApproval,
                "fundingSource", source,
                "authorizationRef", authorizationRef,
                "validationNote", note,
                "validatedAt", Support.stamp());
    }

    /**
     * Message 抛出事件「请求资助批准」的 Worker。
     *
     * <p>投递 FundingApprovalRequested，把外部 Pool（资助机构 / 保险公司）的流程实例启动起来。
     */
    @JobWorker(type = "send-funding-request", autoComplete = false)
    public void sendFundingRequest(final JobClient jobClient, final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();
            String appointmentId = Support.str(variables, "appointmentId", "");

            camundaClient.newPublishMessageCommand()
                    .messageName("FundingApprovalRequested")
                    .correlationKey(appointmentId)
                    .variables(variables)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(variables)
                    .send()
                    .join();

            LOG.info("send-funding-request published FundingApprovalRequested for appointmentId={}", appointmentId);
        } catch (Exception e) {
            LOG.error("Could not publish funding approval request", e);
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }
}
