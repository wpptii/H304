package hpas;

import hpas.db.JobContext;
import hpas.db.PersistenceService;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 选择 1：P1 转诊接收与完整性核查（医院 Pool）
 * 对应模型 p1-referral-intake.bpmn —— 双 Pool 结构，外部方见 {@link GpPoolWorkers}。
 *
 * <p>数据库：{@link PersistenceService} 把每一步的结论落到 referral / patient / process_audit，
 * 写库失败只记日志，绝不改变作业结果（方法返回的 Map 与加库之前完全一致）。
 */
@Component
public class ReferralWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(ReferralWorkers.class);

    /** 演示用：强制判定不完整时使用的缺失项。 */
    private static final List<String> SIMULATED_MISSING_ITEMS = List.of("test results", "medical history");

    private final CamundaClient camundaClient;
    private final PersistenceService db;

    public ReferralWorkers(CamundaClient camundaClient, PersistenceService db) {
        this.camundaClient = camundaClient;
        this.db = db;
    }

    /**
     * 检查材料完整性（BR-01 秘书不得临床评估 / BR-02 完整后转顾问医师）。
     *
     * <p>判定规则刻意做成确定性的，避免误判把人卡在「等补件」：
     * 表单 {@code 缺失的材料} 填了内容 → 不完整；留空 → 完整；
     * 环境变量 SIMULATE_INCOMPLETE_REFERRAL=1 → 强制不完整（演示补件回环）。
     */
    @JobWorker(type = "referral-completeness-check")
    public Map<String, Object> completenessCheck(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String missingText = Support.str(vars, "missingMaterials", "");
        boolean forced = "1".equals(Support.flag("SIMULATE_INCOMPLETE_REFERRAL", "0"));

        List<String> missingItems = new ArrayList<>();
        if (!missingText.isBlank()) {
            for (String s : missingText.split("[,\uFF0C;\uFF1B\\n]+")) {
                if (!s.isBlank()) {
                    missingItems.add(s.trim());
                }
            }
        } else if (forced) {
            missingItems.addAll(SIMULATED_MISSING_ITEMS);
        }

        // 外部 Pool 已回传补充材料（消息带回 supplementProvided）→ 判定为完整，闭环结束
        boolean supplemented = !Support.str(vars, "supplementProvided", "").isBlank();
        if (supplemented) {
            missingItems.clear();
        }

        boolean isComplete = missingItems.isEmpty();

        Support.callExternalSystem("referral-completeness-rules", Support.vars(
                "referralId", vars.get("referralId"),
                "referralSource", vars.get("referralSource"),
                "attachments", vars.get("attachments"),
                "declaredMissing", missingText.isBlank() ? null : missingText));

        LOG.info("referral-completeness-check {} -> isComplete={}", vars.get("referralId"), isComplete);

        // --- 数据库持久化（附加副作用；失败不影响上面的返回结果） ---
        db.recordCompletenessCheck(vars, JobContext.of(job, "referral-completeness-check"));
        // --- 持久化结束 ---

        return Support.vars(
                "isComplete", isComplete,
                "missingItems", missingItems,
                "checkedAt", Support.stamp(),
                "checkNote", isComplete
                        ? (supplemented ? "Supplementary material received; handed over to clinical review" : "All material complete; handed over to clinical review")
                        : "Missing: " + String.join(", ", missingItems) + (forced && missingText.isBlank() ? " (simulated)" : ""));
    }

    /**
     * Message 抛出事件「请求补件」的 Worker（与范例 send-message-01 的写法一致）。
     *
     * <p>投递 ReferralSupplementRequested，把外部 Pool（GP / 其他医院）的流程实例启动起来。
     */
    @JobWorker(type = "send-supplement-request", autoComplete = false)
    public void sendSupplementRequest(final io.camunda.client.api.worker.JobClient jobClient,
                                      final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();
            String referralId = Support.str(variables, "referralId", "");

            // --- 数据库持久化：转诊单登记 + 补件请求审计 ---
            JobContext ctx = JobContext.of(job, "send-supplement-request");
            db.recordReferral(variables, "SUPPLEMENT_REQUESTED", ctx);
            db.recordSupplement(variables, "requested", ctx);
            // --- 持久化结束 ---

            camundaClient.newPublishMessageCommand()
                    .messageName("ReferralSupplementRequested")
                    .correlationKey(referralId)
                    .variables(variables)
                    .timeToLive(java.time.Duration.ofMinutes(10))
                    .send()
                    .join();

            camundaClient.newCompleteCommand(job.getKey())
                    .variables(variables)
                    .send()
                    .join();

            LOG.info("send-supplement-request published ReferralSupplementRequested for referralId={}", referralId);
        } catch (Exception e) {
            LOG.error("Could not publish supplement request", e);
            jobClient.newFailCommand(job)
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage("Could not publish message: " + e.getMessage())
                    .send()
                    .join();
        }
    }

    /** 移交通知：告知顾问医师队列（BR-02）。 */
    @JobWorker(type = "notify-clinical-review")
    public Map<String, Object> notifyClinicalReview(final ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Support.callExternalSystem("clinical-review-queue", Support.vars(
                "referralId", vars.get("referralId"),
                "handedOverTo", vars.get("handedOverTo")));

        // --- 数据库持久化：转诊单进入临床审核 + 审计 ---
        JobContext ctx = JobContext.of(job, "notify-clinical-review");
        db.recordReferral(vars, "UNDER_CLINICAL_REVIEW", ctx);
        db.audit(ctx, "notify-clinical-review", Support.str(vars, "referralId", null),
                "handedOverTo=" + vars.get("handedOverTo"));
        // --- 持久化结束 ---

        return Support.vars("clinicalReviewNotified", true, "handedOverAt", Support.stamp());
    }
}
