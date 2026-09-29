package io.camunda.demo.fundingdetermination;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * P7 资助确定与记录：消息流程集成测试。
 *
 * 使用 camunda-process-test-spring 内置引擎：
 *   - 真实运行 funding-validation / request-funding-approval 两个 @JobWorker；
 *   - 需要审批路径：insurance -> funding-validation 写回 approvalRequired=true ->
 *     request-funding-approval 投递 FundingApprovalRequested ->
 *     外部回执 FundingApprovalResult（按 appointmentId 关联）-> 记录批准 -> 结束；
 *   - 自费路径：self-pay -> approvalRequired=false -> 记录自费/豁免 -> 结束。
 */
@SpringBootTest
@CamundaSpringProcessTest
public class FundingDeterminationApplicationTests {

    private static final String PROCESS_ID = "Process_P7_FundingDetermination";
    private static final String FINANCE_GROUP = "finance-team";

    @Autowired
    private CamundaClient client;

    @Test
    void shouldCompleteApprovalPathViaMessages() throws InterruptedException {
        // given: 部署流程
        client
                .newDeployResourceCommand()
                .addResourceFromClasspath("p7-funding-determination.bpmn")
                .send()
                .join();

        // when: 启动实例（StartEvent_RequestAuthorized 表单变量）
        final ProcessInstanceEvent instance = client
                .newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(Map.of(
                        "appointmentId", "APT-1001",
                        "patientName", "张伟",
                        "treatmentPlan", "膝关节置换"
                ))
                .send()
                .join();

        // 完成「确定资助来源」用户任务（finance-team），登记为保险资助
        completeFundingSourceTask(Map.of(
                "fundingSource", "insurance",
                "authorizationRef", "AUTH-7788",
                "approvedAmount", "12000",
                "restrictions", "仅限住院治疗"
        ));

        // 真实 funding-validation 运行 -> approvalRequired=true
        // 真实 request-funding-approval 运行 -> 投递 FundingApprovalRequested
        // 引擎在 Catch_ApprovalResult 等待 FundingApprovalResult

        // 资助机构回执审批结果（消息关联键 = appointmentId）
        client
                .newPublishMessageCommand()
                .messageName("FundingApprovalResult")
                .correlationKey("APT-1001")
                .variables(Map.of(
                        "approvalStatus", "approved",
                        "approvedAmount", "12000",
                        "restrictions", "仅限住院治疗"
                ))
                .timeToLive(Duration.ofMinutes(10))
                .send()
                .join();

        // 完成「记录批准」用户任务
        completeUserTask(Map.of(
                "approvalRecordForm", Map.of(
                        "approvalStatus", "approved",
                        "approvedAmount", "12000",
                        "restrictions", "仅限住院治疗",
                        "recordedBy", "财务-李敏"
                )
        ));

        // then
        CamundaAssert.assertThat(instance).isCompleted();
    }

    @Test
    void shouldCompleteExemptionPathWithoutApproval() throws InterruptedException {
        // given: 部署流程
        client
                .newDeployResourceCommand()
                .addResourceFromClasspath("p7-funding-determination.bpmn")
                .send()
                .join();

        // when: 启动实例，资助来源为自费
        final ProcessInstanceEvent instance = client
                .newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(Map.of(
                        "appointmentId", "APT-2002",
                        "patientName", "王芳",
                        "treatmentPlan", "门诊随访"
                ))
                .send()
                .join();

        // 完成「确定资助来源」用户任务，登记为自费
        completeFundingSourceTask(Map.of(
                "fundingSource", "self-pay",
                "authorizationRef", "",
                "approvedAmount", "0",
                "restrictions", ""
        ));

        // 真实 funding-validation 运行 -> approvalRequired=false
        // 无需投递任何消息，直接走「记录自费 / 豁免」分支

        // 完成「记录自费 / 豁免」用户任务
        completeUserTask(Map.of(
                "exemptionForm", Map.of(
                        "category", "self-pay",
                        "amount", "0",
                        "notes", "患者自行承担",
                        "recordedBy", "财务-李敏"
                )
        ));

        // then
        CamundaAssert.assertThat(instance).isCompleted();
    }

    // ---- helpers ----------------------------------------------------------

    private void completeFundingSourceTask(Map<String, Object> fundingForm) throws InterruptedException {
        completeUserTask(Map.of("fundingForm", fundingForm));
    }

    private void completeUserTask(Map<String, Object> variables) throws InterruptedException {
        long taskKey = awaitUserTaskKey(FINANCE_GROUP);
        client
                .newCompleteUserTaskCommand(taskKey)
                .variables(variables)
                .send()
                .join();
    }

    /** 轮询等待 finance-team 组的用户任务出现，返回第一个任务的 key。 */
    private long awaitUserTaskKey(String candidateGroup) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            List<UserTask> tasks = client
                    .newUserTaskSearchRequest()
                    .filter(f -> f.candidateGroup(candidateGroup))
                    .send()
                    .join()
                    .items();
            if (!tasks.isEmpty()) {
                return tasks.get(0).getUserTaskKey();
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("No user task found for candidate group: " + candidateGroup);
    }
}
