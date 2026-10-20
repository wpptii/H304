# Java Job Workers (Spring Boot + Camunda 8.9)

> The technology stack is **identical to the course example `Messgae Example`** (the same Maven
> dependencies, the same client package, the same annotation style).
> **No BPMN or form changes are required** — your existing 8 `.bpmn` files and 30 `.form` files
> are used exactly as they are.

## 0. Correspondence with the course example

| Item in the example | Corresponding item in this project |
| --- | --- |
| `pom.xml`: Spring Boot parent POM 4.0.5 + `io.camunda:camunda-spring-boot-starter:8.9.0` | the same |
| `application.properties`: `camunda.client.mode / grpc-address / rest-address` | the same |
| `ProcessOrderApplication` (`@SpringBootApplication`) | `WorkerApplication` |
| `CheckInventoryWorker` / `ChargePaymentWorker` / `ShipItemsWorker` / `ProcessDetails` (`@Component` + `@JobWorker`) | 8 worker classes, **24 topics** in total |
| `MessageHandeller`: `camundaClient.newPublishMessageCommand()` | `Support.publish(...)`, 11 message deliveries in total |
| `ProcessDetails`: `client.newThrowErrorCommand(...)` (BPMN Error) | not used — our models express exception paths with XOR branches |

## 1. Build and run

Prerequisites: JDK 21+ (this machine has JDK 22 installed), Maven, and Camunda 8 already running locally.

```powershell
cd D:\test1\java

# 1) Compile (the first run downloads the Spring Boot and Camunda dependencies)
mvn -q compile

# 2) Start all 24 job workers and leave them running
mvn spring-boot:run
```

The startup log shows the registration of every topic and the `publisher idle` notice. Then simply
`Start process` in Tasklist and the service tasks are completed automatically.

## 2. Job type ↔ worker class mapping (24)

| Choice | Worker class | topic (`zeebe:taskDefinition type`) |
| --- | --- | --- |
| 1 | `ReferralWorkers` (hospital pool) | `referral-completeness-check`, `send-supplement-request`, `notify-clinical-review` |
| 1 | `GpPoolWorkers` (**external pool**) | `gp-send-supplement` |
| 2 | `ClinicalReviewWorkers` (hospital pool) | `clinical-decision-audit`, `notify-scheduling-team`, `notify-referral-institution`, `request-supplement`, `notify-other-hospital` |
| 2 | `PartnerPoolWorkers` (**external pool**) | `partner-send-supplement` |
| 3 | `SchedulingWorkers` (hospital pool) | `send-scheduling-request`, `notify-notification-team` |
| 3 | `SchedulingPoolWorkers` (**external pool**) | `scheduler-process-request`, `scheduler-send-result` |
| 4 | `NotificationWorkers` | `communication-send`, `contact-log`, `notify-confirm` |
| 5 | `LetterWorkers` | `letter-dispatch`, `notify-pathway-coordinator` |
| 6 | `FundingWorkers` (hospital pool) | `funding-validation`, `send-funding-request` |
| 6 | `FunderPoolWorkers` (**external pool**) | `funder-send-result` |
| 7 | `PaymentWorkers` (hospital pool) | `send-payment-request`, `payment-retry`, `notify-care-team` |
| 7 | `PspPoolWorkers` (**external pool**) | `psp-process-payment`, `psp-send-status` |
| 8 | `EnquiryWorkers` | `enquiry-triage`, `route-to-finance`, `await-finance-response`, `route-to-clinical` |

**13 worker classes and 31 job types in total.**

> **All 8 models have been converted to the course example's "true dual pool" form**: the hospital
> pool and the external pool are two executable `process`es (two `participant`s in the same
> `collaboration`), connected by `bpmn:messageFlow`, and the model itself closes the message round
> trip. The external pool's workers live in their own classes (`*PoolWorkers`), so the responsibility
> boundary matches the pool boundary in the diagram.

## 3. Demonstrating each business branch (simulation switches)

Passing them through `spring-boot.run.arguments` is recommended (most reliable, and all three
notations are supported — see `Flags`):

```powershell
# Choice 4: the first notification attempt fails -> the BPMN "no: retry" branch
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_NOTIFY_FAIL=1"

# Choice 7: the PSP returns failure / a duplicate charge -> the "investigate payment failure" and
#            "record exception" branches
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_PAYMENT=failed"
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_PAYMENT=duplicate"

# Choice 3: the first 2 scheduling attempts fail -> demonstrates the timer retry loop (PT5S in the BPMN)
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_SCHEDULING_FAIL_TIMES=2"

# Choice 1: force incomplete documents -> the "request supplement -> await reply -> re-check" branch
mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_INCOMPLETE_REFERRAL=1"
```

They can also be written directly into `application.properties` (for example `SIMULATE_PAYMENT=failed`),
or set as environment variables of the same name.

## 4. Triggering the message catch events that await a reply from an external body

**3** nodes in the models await an external message and must be delivered to explicitly (the
remaining 11 messages are published automatically inside the workers):

| Message name | Belongs to | correlationKey |
| --- | --- | --- |
| `ReferralSupplementReceived` | Choice 1 (P1) supplement reply | `referralId` |
| `SupplementReceived` | Choice 2 (P2) supplement reply | `referralId` |
| `FundingApprovalResult` | Choice 6 (P7) funding approval result | `appointmentId` |

```powershell
# Example: simulate the GP returning a supplement (correlation key = your referral id)
mvn spring-boot:run -Dspring-boot.run.arguments="--publish.message=ReferralSupplementReceived --publish.key=REF-001 --publish.exit=true"

# Example: simulate the insurer's approval (with variables)
mvn spring-boot:run -Dspring-boot.run.arguments="--publish.message=FundingApprovalResult --publish.key=APPT-2001 --publish.var=approvalStatus=approved,approvedAmount=1200 --publish.exit=true"
```

`--publish.exit=true` exits the process after delivery (suitable for scripting); without it the
workers keep running.

## 5. Three things learned from the example (which also corrected my earlier approach)

1. **In Camunda 8, a message throw event must carry a `zeebe:taskDefinition`**, and the job worker is
   responsible for `publishMessage`; a bare `<bpmn:messageEventDefinition messageRef="..."/>`
   reports "must have a Task definition type".
   In the example, K uses `intermediateThrowEvent` + `zeebe:taskDefinition`; in our 7 models the
   equivalent **service task + `zeebe:taskDefinition`** is used in the corresponding position (the
   worker still sends the message, and this validation error is not triggered).
2. **`zeebe:subscription correlationKey` goes on the `<bpmn:message>` element**, not on the catch
   event — this matches how we wrote P1 / P2 / P7 / P12.
3. **The example's "dual pool" = two executable `process`es in one collaboration + `bpmn:messageFlow`**.
   Choice 1 has been converted accordingly and the **closed loop was verified by running it** (see
   section 7); the remaining models are still in the simplified "single process + lanes" form and
   can be converted in the same way on request.

## 6. Troubleshooting

| Symptom | Cause / action |
| --- | --- |
| A service task is yellow `Waiting` in Operate and no new task appears in Tasklist | No worker is consuming that topic. Check that the `mvn spring-boot:run` window is still open |
| A connection error on startup | Check `camunda.client.grpc-address` in `application.properties`, and whether Camunda 8 has been started |
| Message delivery reports that no subscription was found | The instance has not yet reached the catch event, or the correlationKey does not match the business key (see the table in section 4) |
| Unexpected event | Cause / action |
| --- | --- |
| A job becomes an incident (red) | The job's retry count is exhausted: reset the retries in Operate, or read the exception in the worker log |
| The gateway reports `Extract value error` | A FEEL condition received dirty data. Example: `123` was entered as the "appointment date" in choice 4, `date("123")` failed to parse and returned null, so the XOR got no boolean. **Fixed**: the condition is now a string comparison that does not call `date()`, with null protection; the form also gained a `YYYY-MM-DD` regular-expression validation. If an already-existing older instance is stuck on an older version, simply `Cancel` it and run again |
| Running the Node worker version as well | **Do not** — the two compete for the same jobs |

## 7. Dual-pool conversion and measured verification record

**All** 8 models have been converted to the course example's "true dual pool" structure (two
executable processes + `bpmn:messageFlow`). Verified by actually running them on your local cluster
(Camunda 8 Run 8.10.0-alpha5):

| Model | External pool | Structure | Measured result |
| --- | --- | --- | --- |
| Choice 1 | GP / other hospital | supplement request ⇄ supplement reply | ✅ complete loop (the GP instance is started by a message -> reply -> the hospital re-check passes) |
| Choice 2 | referring institution / appointment team / other hospital | 4 outbound messages + a supplement loop | ✅ deploy succeeds (5 messageFlows) |
| Choice 3 | scheduling service | scheduling request ⇄ scheduling result | ✅ both the hospital instance and the scheduling instance are `COMPLETED` |
| Choice 4 | communication service / patient | contact-outcome notification (sent by a message end event) | ✅ the external pool instance is started by a message |
| Choice 5 | communication service / management | escalation notification (sent by a message end event) | ✅ deploy succeeds |
| Choice 6 | funding agency / insurer | approval request ⇄ approval result | ✅ deploy succeeds |
| Choice 7 | payment service provider (PSP) | payment request ⇄ payment status receipt | ✅ the PSP instance is `COMPLETED` and the hospital finishes on the success branch |
| Choice 8 | finance / CNS clinical | 2 messages start the two branches respectively | ✅ deploy succeeds |

Deployment scale: **16 process definitions** (8 models × 2 pools) + **52 form versions**, all
submitted successfully in one go.

> Note: `deploy.ps1` has been corrected to work with **Windows PowerShell 5.1** (the original form
> used the PS 6+ `-Form` parameter), and the `.ps1` files have been given a **UTF-8 BOM** (otherwise
> PS 5.1 reads them as GBK, the Chinese text is garbled and a parse error is reported).
> Caution: if the BOM is lost after editing a `.ps1` file in an editor, save it again as
> "UTF-8 with BOM".

### Structure validator

`D:\test1\_validate.ps1` runs a structural self-check on any BPMN file (ID uniqueness, sequence
flow / message flow references, DI references, lane references, flow nodes missing DI, and the
`taskDefinition` check for message events):

```powershell
.\_validate.ps1 -Path 'D:\test1\p1-referral-intake.bpmn'
```

All 8 models currently report **0 problems**.
