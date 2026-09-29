package io.camunda.demo.letter_process;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static io.camunda.process.test.api.assertions.UserTaskSelectors.byElementId;

@SpringBootTest
@CamundaSpringProcessTest
public class LetterProcessApplicationTests {

    private static final String PROCESS_ID = "Process_P10_LetterProcess";

    @Autowired
    private CamundaClient client;
    @Autowired
    private CamundaProcessTestContext processTestContext;

    @Test
    void shouldDispatchOnTimeWhenWithinDeadline() {
        // given: the process is deployed and a case is started
        deployProcess();
        ProcessInstanceEvent processInstance = startInstance(Map.of(
                "caseId", "CASE-001",
                "intakeDate", "2026-07-01",
                "patientName", "张三",
                "clinicName", "门诊科"
        ));
        long pi = processInstance.getProcessInstanceKey();

        // 消息 worker 只在升级路径触发，这里 mock 保证即使误走到升级也能完成
        processTestContext.mockJobWorker("notify-pathway-coordinator").thenComplete();

        // 顾问撰写信函（2 天内完成 -> 真实 letter-dispatch worker 应判定按时）
        processTestContext.completeUserTask(byElementId("Task_DraftLetter", pi), Map.of(
                "letterForm", Map.of(
                        "letterType", "clinic-summary",
                        "letterBody", "门诊就诊总结正文",
                        "draftedBy", "顾问A"),
                "letterDraftCompletedAt", "2026-07-02T10:00:00Z"
        ));
        // 顾问批准信函
        processTestContext.completeUserTask(byElementId("Task_ApproveLetter", pi), Map.of(
                "approvalForm", Map.of(
                        "decision", "approve",
                        "comments", "同意",
                        "approvedBy", "顾问A")
        ));
        // 医疗秘书行政检查
        processTestContext.completeUserTask(byElementId("Task_AdminCheck", pi), Map.of(
                "checksForm", Map.of(
                        "administrativeCheck", "pass",
                        "addressee", "张三 / 医院",
                        "addresseeVerified", true,
                        "formatVerified", true)
        ));

        // then: 分发按时 -> 直接结束（不经过升级）
        CamundaAssert.assertThat(processInstance).isCompleted();
    }

    @Test
    void shouldEscalateWhenDispatchOverdue() {
        // given
        deployProcess();
        ProcessInstanceEvent processInstance = startInstance(Map.of(
                "caseId", "CASE-002",
                "intakeDate", "2026-07-01",
                "patientName", "李四",
                "clinicName", "门诊科"
        ));
        long pi = processInstance.getProcessInstanceKey();

        processTestContext.mockJobWorker("notify-pathway-coordinator").thenComplete();

        // 顾问撰写信函（9 天后才完成 -> 真实 letter-dispatch worker 应判定逾期）
        processTestContext.completeUserTask(byElementId("Task_DraftLetter", pi), Map.of(
                "letterForm", Map.of(
                        "letterType", "referral-letter",
                        "letterBody", "转诊信正文",
                        "draftedBy", "顾问B"),
                "letterDraftCompletedAt", "2026-07-10T10:00:00Z"
        ));
        processTestContext.completeUserTask(byElementId("Task_ApproveLetter", pi), Map.of(
                "approvalForm", Map.of(
                        "decision", "approve",
                        "comments", "同意",
                        "approvedBy", "顾问B")
        ));
        processTestContext.completeUserTask(byElementId("Task_AdminCheck", pi), Map.of(
                "checksForm", Map.of(
                        "administrativeCheck", "pass",
                        "addressee", "李四 / 医院",
                        "addresseeVerified", true,
                        "formatVerified", true)
        ));
        // 逾期 -> 升级行政经理 / 管理层
        processTestContext.completeUserTask(byElementId("Task_Escalate", pi), Map.of(
                "escalationForm", Map.of(
                        "escalationReason", "超过 7 天目标未完成",
                        "decision", "dispatch-with-delay-note",
                        "escalatedBy", "行政经理")
        ));

        // then: 升级后通知协调员（mock 完成）-> 结束
        CamundaAssert.assertThat(processInstance).isCompleted();
    }

    private void deployProcess() {
        client.newDeployResourceCommand()
                .addResourceFromClasspath("p10-letter-process.bpmn")
                .send()
                .join();
    }

    private ProcessInstanceEvent startInstance(Map<String, Object> variables) {
        return client.newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(variables)
                .send()
                .join();
    }
}
