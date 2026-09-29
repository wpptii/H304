package io.camunda.demo.fundingdetermination;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * External Worker：验证资助（Job type = funding-validation）。
 *
 * 对应 BPMN 服务任务 Task_ValidateFunding（p7-funding-determination.bpmn）。
 * BPMN 上的 IO Mapping 已把 fundingForm 各字段映射为本地变量
 * （fundingSource / authorizationRef / approvedAmount / restrictions / appointmentId），
 * 因此这里直接按这些 key 读取。
 *
 * 业务规则（BR-14 / BR-15 / BR-17，支付前置门控）：
 *   - insurance / funding-agency 必须携带授权参考号 authorizationRef；
 *   - approvedAmount 若填写必须是合法非负数字；
 *   - approvalRequired：只有外部资助（保险 / 资助机构）需要走审批消息；
 *     自费 / 院内经费 / 豁免走「记录自费/豁免」分支。
 *
 * 写回 fundingValid 与 approvalRequired 供排他网关 Gateway_ApprovalRequired 使用。
 */
@Component
public class FundingValidationWorker {

    private static final Logger LOG = LoggerFactory.getLogger(FundingValidationWorker.class);

    /** 需要外部审批的资助来源（BR-14：保险 / 资助机构）。 */
    private static final java.util.Set<String> EXTERNAL_APPROVAL_SOURCES =
            java.util.Set.of("insurance", "funding-agency");

    @JobWorker(type = "funding-validation")
    public Map<String, Object> validateFunding(final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        String fundingSource  = asString(variables.get("fundingSource"));
        String authorizationRef = asString(variables.get("authorizationRef"));
        String approvedAmount   = asString(variables.get("approvedAmount"));
        String restrictions     = asString(variables.get("restrictions"));
        String appointmentId    = asString(variables.get("appointmentId"));

        LOG.info("funding-validation 开始，appointmentId={}, fundingSource={}",
                appointmentId, fundingSource);

        boolean fundingValid = true;
        List<String> issues = new ArrayList<>();

        // BR-14：保险 / 资助机构必须有授权参考号，否则视为无效资助
        if (EXTERNAL_APPROVAL_SOURCES.contains(fundingSource)
                && (authorizationRef == null || authorizationRef.isBlank())) {
            fundingValid = false;
            issues.add("授权参考号缺失");
        }

        // 批准金额：若填写则必须是合法非负数字
        if (approvedAmount != null && !approvedAmount.isBlank()) {
            try {
                if (Double.parseDouble(approvedAmount) < 0) {
                    fundingValid = false;
                    issues.add("批准金额不能为负");
                }
            } catch (NumberFormatException e) {
                fundingValid = false;
                issues.add("批准金额格式非法");
            }
        }

        // BR-15：只有外部资助需要审批消息；自费 / 豁免不需要
        boolean approvalRequired = EXTERNAL_APPROVAL_SOURCES.contains(fundingSource);

        Map<String, Object> result = new HashMap<>();
        result.put("fundingValid", fundingValid);
        result.put("approvalRequired", approvalRequired);
        result.put("validationIssues", String.join("; ", issues));
        result.put("fundingValidatedAt", OffsetDateTime.now().toString());

        LOG.info("funding-validation 完成，fundingValid={}, approvalRequired={}, issues={}",
                fundingValid, approvalRequired, issues);

        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
