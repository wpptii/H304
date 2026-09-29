# Java 版 Job Worker（Spring Boot + Camunda 8.9）

> 技术栈与课程范例 **`Messgae Example` 完全一致**（同一套 Maven 依赖、同一个客户端包、同一种注解写法）。
> **BPMN 与表单不需要任何改动** —— 你现有的 8 个 `.bpmn` 和 30 个 `.form` 原样使用。

## 0. 与课程范例的对应关系

| 范例里的东西 | 本项目对应 |
| --- | --- |
| `pom.xml`：Spring Boot 父 POM 4.0.5 + `io.camunda:camunda-spring-boot-starter:8.9.0` | 相同 |
| `application.properties`：`camunda.client.mode / grpc-address / rest-address` | 相同 |
| `ProcessOrderApplication`（`@SpringBootApplication`） | `WorkerApplication` |
| `CheckInventoryWorker` / `ChargePaymentWorker` / `ShipItemsWorker` / `ProcessDetails`（`@Component` + `@JobWorker`） | 8 个 Worker 类，共 **24 个 topic** |
| `MessageHandeller`：`camundaClient.newPublishMessageCommand()` | `Support.publish(...)`，共 11 处消息投递 |
| `ProcessDetails`：`client.newThrowErrorCommand(...)`（BPMN Error） | 未使用 —— 我们的模型用 XOR 分支表达异常路径 |

## 1. 构建与运行

前置：JDK 21+（本机已装 JDK 22）、Maven、Camunda 8 已在本机运行。

```powershell
cd D:\test1\java

# 1) 编译（首次会下载 Spring Boot 与 Camunda 依赖）
mvn -q compile

# 2) 启动全部 24 个 Job Worker —— 保持运行
mvn spring-boot:run
```

启动日志里会看到每个 topic 的注册信息与 `publisher idle` 提示。然后在 Tasklist 里 `Start process` 即可，
服务任务会被自动完成。

## 2. Job Type ↔ Worker 类对照（24 个）

| 选择 | Worker 类 | topic（`zeebe:taskDefinition type`） |
| --- | --- | --- |
| 1 | `ReferralWorkers`（医院 Pool） | `referral-completeness-check`、`send-supplement-request`、`notify-clinical-review` |
| 1 | `GpPoolWorkers`（**外部 Pool**） | `gp-send-supplement` |
| 2 | `ClinicalReviewWorkers`（医院 Pool） | `clinical-decision-audit`、`notify-scheduling-team`、`notify-referral-institution`、`request-supplement`、`notify-other-hospital` |
| 2 | `PartnerPoolWorkers`（**外部 Pool**） | `partner-send-supplement` |
| 3 | `SchedulingWorkers`（医院 Pool） | `send-scheduling-request`、`notify-notification-team` |
| 3 | `SchedulingPoolWorkers`（**外部 Pool**） | `scheduler-process-request`、`scheduler-send-result` |
| 4 | `NotificationWorkers` | `communication-send`、`contact-log`、`notify-confirm` |
| 5 | `LetterWorkers` | `letter-dispatch`、`notify-pathway-coordinator` |
| 6 | `FundingWorkers`（医院 Pool） | `funding-validation`、`send-funding-request` |
| 6 | `FunderPoolWorkers`（**外部 Pool**） | `funder-send-result` |
| 7 | `PaymentWorkers`（医院 Pool） | `send-payment-request`、`payment-retry`、`notify-care-team` |
| 7 | `PspPoolWorkers`（**外部 Pool**） | `psp-process-payment`、`psp-send-status` |
| 8 | `EnquiryWorkers` | `enquiry-triage`、`route-to-finance`、`await-finance-response`、`route-to-clinical` |

**共 13 个 Worker 类、31 个 Job Type。**

> **全部 8 个模型都已按课程范例改成「真双 Pool」**：医院 Pool 与外部 Pool 是两个可执行 `process`
> （同一 `collaboration` 下的两个 `participant`），通过 `bpmn:messageFlow` 连接，消息往返由模型自己闭环。
> 外部 Pool 的 Worker 单独成类（`*PoolWorkers`），职责边界与图形上的 Pool 边界一致。

## 3. 演示各条业务分支（模拟开关）

推荐用 `spring-boot.run.arguments` 传（最稳，三种写法都支持，见 `Flags`）：

```powershell
# 选择 4：首次发送通知失败 → 走 BPMN 的「否：重试」分支
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_NOTIFY_FAIL=1"

# 选择 7：PSP 返回失败 / 重复扣款 → 走「调查支付失败」/「记录异常」两条分支
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_PAYMENT=failed"
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_PAYMENT=duplicate"

# 选择 3：前 2 次排程失败 → 演示 Timer 重试环（BPMN 里是 PT5S）
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_SCHEDULING_FAIL_TIMES=2"

# 选择 1：强制材料不完整 → 走「请求补件 → 等回件 → 重新核查」
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_INCOMPLETE_REFERRAL=1"
```

也可以直接写在 `application.properties` 里（例如 `SIMULATE_PAYMENT=failed`），或设同名环境变量。

## 4. 触发「等外部机构回件」的消息捕获事件

模型里有 **3 个** 节点在等外部消息，需要主动投递（其余 11 个消息由 Worker 内部自动 publish）：

| 消息名 | 属于 | correlationKey |
| --- | --- | --- |
| `ReferralSupplementReceived` | 选择 1（P1）补件回复 | `referralId` |
| `SupplementReceived` | 选择 2（P2）补件回复 | `referralId` |
| `FundingApprovalResult` | 选择 6（P7）资助审批结果 | `appointmentId` |

```powershell
# 例：模拟 GP 回传补件（关联键 = 你的转诊号）
mvn spring-boot:run -Dspring-boot.run.arguments="--publish.message=ReferralSupplementReceived --publish.key=REF-001 --publish.exit=true"

# 例：模拟保险公司批复（带变量）
mvn spring-boot:run -Dspring-boot.run.arguments="--publish.message=FundingApprovalResult --publish.key=APPT-2001 --publish.var=approvalStatus=approved,approvedAmount=1200 --publish.exit=true"
```

`--publish.exit=true` 表示投递后退出进程（适合脚本化）；不加则继续运行 Worker。

## 5. 从范例里学到的三点（也修正了我的早期做法）

1. **Camunda 8 的消息抛出事件必须挂 `zeebe:taskDefinition`**，由 Job Worker 负责 `publishMessage`；
   单纯的 `<bpmn:messageEventDefinition messageRef="..."/>` 会报
   「must have a Task definition type」。
   范例里 K 用的是 `intermediateThrowEvent` + `zeebe:taskDefinition`；我们的 7 个模型里对应位置用的是
   等价的 **服务任务 + `zeebe:taskDefinition`**（同样由 Worker 发消息，且不会触发该校验错误）。
2. **`zeebe:subscription correlationKey` 写在 `<bpmn:message>` 元素上**，不是写在捕获事件上 ——
   这与我们 P1 / P2 / P7 / P12 的写法一致。
3. **范例的「双 Pool」= 同一个 collaboration 里两个可执行 `process` + `bpmn:messageFlow`**。
   选择 1 已按此改造完成并**实测跑通闭环**（见第 7 节）；其余模型仍是「单流程 + 泳道」的简化形式，
   如需一并改造请告知。

## 6. 排查

| 现象 | 原因 / 处理 |
| --- | --- |
| Operate 里服务任务黄色 `Waiting`、Tasklist 没有新任务 | 没有 Worker 消费该 topic。确认 `mvn spring-boot:run` 窗口还开着 |
| 启动报连接错误 | 检查 `application.properties` 的 `camunda.client.grpc-address`，以及 Camunda 8 是否已启动 |
| 消息投递报找不到订阅 | 实例还没走到捕获事件，或 correlationKey 与业务号不一致（见第 4 节表格） |
| 意外 | 原因 / 处理 |
| --- | --- |
| Job 变 incident（红色） | 作业重试次数用尽：在 Operate 重置 retries，或看 Worker 日志里的异常 |
| Gateway 报 `Extract value error` | FEEL 条件取到了脏数据。例：选择 4 的「预约日期」填了 `123`，`date("123")` 解析失败返回 null，XOR 拿不到布尔值。**已修**：条件改为不调用 `date()` 的字符串比较并做 null 保护；表单也加了 `YYYY-MM-DD` 正则校验。若已存在的旧实例卡住且版本较老，直接 `Cancel` 掉重跑 |
| 想同时用 Node 版 Worker | **不要**，两套会抢同一批作业 |

## 7. 双 Pool 改造与实测验证记录

8 个模型**全部**已改成课程范例的「真双 Pool」结构（两个可执行 process + `bpmn:messageFlow`）。
在你的本地集群（Camunda 8 Run 8.10.0-alpha5）上真实跑通：

| 模型 | 外部 Pool | 结构 | 实测 |
| --- | --- | --- | --- |
| 选择 1 | GP / 其他医院 | 请求补件 ⇄ 补件回传 | ✅ 完整闭环（GP 实例被消息启动 → 回传 → 医院重新核查通过） |
| 选择 2 | 转诊机构 / 预约团队 / 其他医院 | 4 条出向消息 + 补件回环 | ✅ 部署通过（5 条 messageFlow） |
| 选择 3 | 排程服务 | 排程请求 ⇄ 排程结果 | ✅ 医院实例与排程实例均 `COMPLETED` |
| 选择 4 | 通信服务 / 患者 | 联系结果通知（消息结束事件发出） | ✅ 外部 Pool 实例被消息启动 |
| 选择 5 | 通信服务 / 管理层 | 升级通知（消息结束事件发出） | ✅ 部署通过 |
| 选择 6 | 资助机构 / 保险公司 | 审批请求 ⇄ 批准结果 | ✅ 部署通过 |
| 选择 7 | 支付服务提供商 PSP | 支付请求 ⇄ 支付状态回执 | ✅ PSP 实例 `COMPLETED`，医院走成功分支完成 |
| 选择 8 | 财务 / CNS 临床 | 2 条消息分别启动两条分支 | ✅ 部署通过 |

部署规模：**16 个流程定义**（8 模型 × 2 Pool）+ **52 个表单版本**，全部一次提交成功。

> 提示：`deploy.ps1` 已修正为兼容 **Windows PowerShell 5.1**（原写法用了 PS 6+ 的 `-Form`），
> 并且 `.ps1` 都补了 **UTF-8 BOM**（否则 PS 5.1 会按 GBK 读，中文乱码并报解析错误）。
> 注意：用编辑器改过 `.ps1` 后如果 BOM 丢了，需要重新另存为「UTF-8 with BOM」。

### 结构校验器

`D:\test1\_validate.ps1` 可对任意 BPMN 做结构自检（ID 唯一性、顺序流/消息流引用、DI 引用、泳道引用、
流节点是否缺 DI、消息事件的 `taskDefinition` 校验）：

```powershell
.\_validate.ps1 -Path 'D:\test1\p1-referral-intake.bpmn'
```

当前 8 个模型全部 **0 问题**。
