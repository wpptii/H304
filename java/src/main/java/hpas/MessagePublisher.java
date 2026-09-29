package hpas;

import io.camunda.client.CamundaClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 投递「等外部机构回件」的消息 —— 用于触发模型里的消息捕获事件。
 *
 * <p>与范例 MessageHandeller 的 publish 写法一致（CamundaClient + newPublishMessageCommand）。
 *
 * <p>用法（不设置 publish.message 时本组件什么都不做，只启动 Worker）：
 * <pre>
 *   mvn -q spring-boot:run -Dspring-boot.run.arguments="--publish.message=ReferralSupplementReceived --publish.key=REF-001"
 *   mvn -q spring-boot:run -Dspring-boot.run.arguments="--publish.message=FundingApprovalResult --publish.key=APPT-2001 --publish.var=approvalStatus=approved,approvedAmount=1200"
 * </pre>
 *
 * <p>三个可用消息（correlationKey 必须等于流程实例里的业务号）：
 * <ul>
 *   <li>{@code ReferralSupplementReceived} —— 选择 1（P1）补件回复，key = referralId</li>
 *   <li>{@code SupplementReceived}         —— 选择 2（P2）补件回复，key = referralId</li>
 *   <li>{@code FundingApprovalResult}       —— 选择 6（P7）资助审批结果，key = appointmentId</li>
 * </ul>
 */
@Component
public class MessagePublisher implements ApplicationRunner {

    private final CamundaClient camundaClient;

    @Value("${publish.message:}")
    private String messageName;

    @Value("${publish.key:}")
    private String correlationKey;

    /** 逗号分隔的 k=v 变量，例如 approvalStatus=approved,approvedAmount=1200 */
    @Value("${publish.var:}")
    private String variablePairs;

    @Value("${publish.exit:false}")
    private boolean exitAfterPublish;

    public MessagePublisher(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (messageName == null || messageName.isBlank()) {
            Support.log("publisher", Support.vars(
                    "status", "idle",
                    "hint", "未设置 publish.message，仅启动 Worker。发消息见 README 第 4 节"));
            return;
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        if (variablePairs != null && !variablePairs.isBlank()) {
            for (String pair : variablePairs.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    variables.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }

        try {
            camundaClient.newPublishMessageCommand()
                    .messageName(messageName)
                    .correlationKey(correlationKey == null ? "" : correlationKey)
                    .variables(variables)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();

            Support.log("message-published", Support.vars(
                    "messageName", messageName,
                    "correlationKey", correlationKey,
                    "variables", variables));
        } catch (Exception e) {
            Support.log("message-failed", Support.vars(
                    "messageName", messageName,
                    "correlationKey", correlationKey,
                    "error", String.valueOf(e.getMessage()),
                    "hint", "确认流程实例已到达对应捕获事件，且关联键与业务号一致"));
        }

        if (exitAfterPublish) {
            System.exit(0);
        }
    }
}
