# P7 资助确定与记录（嗡嗡嗡2）— Java Worker / Spring Boot

基于 **Messgae Example**（`io.camunda.demo.process_order`）改写的 Camunda 8 消息流程示例，
对应 `p7-funding-determination.bpmn`（选择 6：资助确定与记录）。

## 工程结构

```
嗡嗡嗡2/
├── p7-funding-determination.bpmn      # BPMN 流程（已存在，无需改动）
└── java/                              # 本 Java 工程
    ├── pom.xml
    └── src/
        ├── main/java/io/camunda/demo/fundingdetermination/
        │   ├── FundingDeterminationApplication.java      # Spring Boot 入口
        │   ├── FundingValidationWorker.java              # funding-validation
        │   └── FundingApprovalMessageHandler.java        # request-funding-approval（消息投递）
        └── test/java/io/camunda/demo/fundingdetermination/
            └── FundingDeterminationApplicationTests.java # 两条路径的消息集成测试
```

## 与 Messgae Example 的对应关系

| 本项目（P7）                      | 示例工程                        | 说明                              |
|-----------------------------------|--------------------------------|-----------------------------------|
| `funding-validation` Worker       | `check-inventory` 等普通 Worker | 写回 `fundingValid` / `approvalRequired` |
| `request-funding-approval` Worker | `send-message-01/02` Handler   | `autoComplete=false`，投递后手动 complete |
| 消息 `FundingApprovalRequested`   | 消息 `Message01`               | 由本 Worker 投递               |
| 消息 `FundingApprovalResult`      | 消息 `Message02`               | 中间捕获，关联键 = `appointmentId` |

## 前置条件

- JDK 21–25
- Maven（IDEA 自带或独立安装均可）
- 本地 Camunda 8 集群（self-managed，默认 `127.0.0.1:26500` / `8080`）

## 运行

```bash
cd java
mvn spring-boot:run
```

停：Ctrl+C。

## 测试

```bash
cd java
mvn test
```

用 `camunda-process-test-spring` 起内置引擎，无需本地集群即可跑通两条路径：
1. `insurance` → 审批消息往返 → 「记录批准」→ 结束；
2. `self-pay` → 无审批 → 「记录自费/豁免」→ 结束。

## 业务规则（编码于 FundingValidationWorker）

- **BR-14**：`insurance` / `funding-agency` 必须携带授权参考号，否则 `fundingValid=false`；
- **BR-15**：只有外部资助（保险 / 资助机构）`approvalRequired=true`，自费 / 豁免走免审批分支；
- **BR-17**：审批回执由消息关联键 `appointmentId` 自动关联，Java 无需监听。
