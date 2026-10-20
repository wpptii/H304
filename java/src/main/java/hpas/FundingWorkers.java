package hpas;

import hpas.db.JobContext;
import hpas.db.PersistenceService;
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
 *
 * <p>数据库：资助验证结论 / 批准金额 / 限制条件写入 funding_case 表（caseId 唯一）。
 */
@Component
public class FundingWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(FundingWorkers.class);

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public FundingWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
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
            note = source + ": no external approval required; recorded as self-pay / exemption";
        } else if (hasRef) {
            note = "Authorisation reference " + authorizationRef + " registered; awaiting "
                    + ("insurance".equals(source) ? "insurance provider" : "funding agency") + " approval confirmation";
        } else {
            note = "Authorisation reference missing; routed for approval";
        }

        Map<String, Object> result = Support.vars(
                "fundingValid", needsApproval ? hasRef : true,
                "approvalRequired", needsApproval,
                "fundingSource", source,
                "authorizationRef", authorizationRef,
                "validationNote", note,
                "validatedAt", Support.stamp());

        // --- 数据库持久化：资助案例登记（funding_case 表） ---
        Map<String, Object> merged = new java.util.LinkedHashMap<>(vars);
        merged.putAll(result);
        db.recordFunding(merged, JobContext.of(job, "funding-validation"));
        // --- 持久化结束 ---

        return result;
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

            // --- 数据库持久化：审批请求已发出（funding_case.status=pending + 审计） ---
            db.recordFunding(variables, JobContext.of(job, "send-funding-request"));
            // --- 持久化结束 ---

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
