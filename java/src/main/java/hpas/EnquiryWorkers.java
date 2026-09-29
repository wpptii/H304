package hpas;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 选择 8：P8 患者咨询处理与路由
 * 对应模型 p8-inquiry-routing.bpmn（简单行政 / 财务 / 临床三条分支）。
 */
@Component
public class EnquiryWorkers {

    private static final List<String> URGENT_KEYWORDS =
            List.of("胸痛", "出血", "呼吸困难", "昏迷", "抽搐", "自杀", "剧烈疼痛", "胸闷");

    private final CamundaClient camundaClient;

    public EnquiryWorkers(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    /** 初步分类 / 紧急度建议（BR-31：紧急关键词必须随消息传递）。 */
    @JobWorker(type = "enquiry-triage")
    public Map<String, Object> enquiryTriage(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String inquiryType = Support.str(vars, "inquiryType", "");
        String keywordText = Support.str(vars, "urgentKeywords", "");

        List<String> matched = new ArrayList<>();
        for (String k : keywordText.split("[,，;；\\s]+")) {
            if (k.isBlank()) {
                continue;
            }
            for (String u : URGENT_KEYWORDS) {
                if (k.contains(u) || u.contains(k)) {
                    matched.add(k);
                    break;
                }
            }
        }
        boolean urgent = !matched.isEmpty() || "clinical".equals(inquiryType);

        Support.callExternalSystem("enquiry-triage-rules", Support.vars(
                "enquiryId", vars.get("enquiryId"),
                "inquiryType", inquiryType,
                "content", vars.get("content"),
                "urgentKeywords", keywordText));

        return Support.vars(
                "triageUrgency", urgent ? "urgent" : "routine",
                "triageNote", urgent
                        ? "命中紧急指征：" + (matched.isEmpty() ? "临床类咨询" : String.join("、", matched)) + "，建议优先处理"
                        : "无明显紧急指征，按常规时限处理",
                "triagedAt", Support.stamp());
    }

    /** Message：转财务 Pool。 */
    @JobWorker(type = "route-to-finance")
    public Map<String, Object> routeToFinance(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.publish(camundaClient, "InquiryRoutedToFinance",
                Support.str(vars, "enquiryId", ""),
                Support.vars(
                        "enquiryId", vars.get("enquiryId"),
                        "content", vars.get("content"),
                        "urgency", vars.get("urgency")));
        return Support.vars("messageSent", true, "messageName", "InquiryRoutedToFinance", "sentAt", Support.stamp());
    }

    /** 等待财务 Pool 处理回执（OQ-01）。 */
    @JobWorker(type = "await-finance-response")
    public Map<String, Object> awaitFinanceResponse(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.callExternalSystem("finance-pool-receipt", Support.vars("enquiryId", vars.get("enquiryId")));
        return Support.vars("financeHandled", true, "financeHandledAt", Support.stamp());
    }

    /** Message：转 CNS / 临床 Pool（带 urgency，紧急时接收方高亮）。 */
    @JobWorker(type = "route-to-clinical")
    public Map<String, Object> routeToClinical(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String urgency = Support.str(vars, "urgency", "routine");
        Support.publish(camundaClient, "InquiryRoutedToClinical",
                Support.str(vars, "enquiryId", ""),
                Support.vars(
                        "enquiryId", vars.get("enquiryId"),
                        "content", vars.get("content"),
                        "urgency", urgency,
                        "urgentKeywords", vars.get("urgentKeywords"),
                        "highlight", "urgent".equals(urgency)));
        return Support.vars("messageSent", true, "messageName", "InquiryRoutedToClinical", "sentAt", Support.stamp());
    }
}
