# HPAS persistence layer (Spring Data JPA + H2 file database)

> This document answers one question: **why are Camunda process variables not enough, and why must
> there also be a database?**
> And: **which table does each process step actually write to, and what does it do?**
>
> Code location: `src/main/java/hpas/db/` (domain / repo / service), `src/main/java/hpas/api/`
> (read-only demonstration API).
> Related files: [demo-queries.sql](demo-queries.sql) (runnable queries),
> [ER-diagram.md](ER-diagram.md) (entity / relationship diagram).

---

## 1. The conclusion in one sentence

Camunda process variables **live only for the lifetime of the process instance**:

- once the instance is `COMPLETED` (or has been cleaned up under Operate's retention policy) the
  variables can no longer be queried;
- variables are organised **per instance**, so questions such as "what is the completeness rate of
  all referrals this quarter" or "has the same charge been deducted twice" can only be answered
  with database SQL, not by opening instances one at a time;
- variables have no **unique constraint / foreign key**, so they cannot prevent duplicate charges
  or duplicate master data;
- Operate's audit trail takes the **engine's point of view** (jobs, variables, incidents), not the
  **business point of view** (who did what to which patient).

What we have to deliver is "business data + management KPIs + compliance audit", so a database is
added at this layer:
**the process is unchanged (not one line of BPMN, not one variable has been touched), but the
outcome of every step lands in a real relational database file.**

---

## 2. Directories and files

| Location | Contents |
| --- | --- |
| `hpas/db/domain/` | 11 JPA entities (`@Entity`): `Referral` `Patient` `Appointment` `ClinicalDecision` `ClinicLetter` `FundingCase` `Payment` `Enquiry` `AppUser` `UserRole` `ProcessAudit` |
| `hpas/db/repo/` | 11 Spring Data repositories (`JpaRepository`), including business queries such as `existsByCaseIdAndStatus` |
| `hpas/db/PersistenceService.java` | **the single write entry point**: `recordReferral` / `recordCompletenessCheck` / `recordSupplement` / `recordClinicalDecision` / `upsertAppointment` / `recordFunding` / `recordPayment` / `recordLetter` / `recordEscalation` / `recordEnquiry` / `audit` |
| `hpas/db/Values.java` | safe variable reading (null, wrong type and over-length values never throw) |
| `hpas/db/JobContext.java` | extracts process coordinates from `ActivatedJob` (processInstanceKey / elementId / jobType / jobKey…) |
| `hpas/db/DatabaseSeeder.java` | idempotently seeds 7 users + 8 roles at startup (demonstrates the permission model) |
| `hpas/api/DbDemoController.java` | read-only demonstration API `/api/db/**` (port 8090) |
| `db/demo-queries.sql` | 14 runnable SQL statements (including KPIs, the full chain, audit and the duplicate-charge check) |
| `db/ER-diagram.md` | relationships and constraints of the 11 entities / 11 tables (including a Mermaid `erDiagram`) |

---

## 3. Why a database is still needed alongside Camunda (point by point)

| Requirement | Consequence of relying on process variables alone | How the database solves it |
| --- | --- | --- |
| **Surviving beyond the instance lifecycle** | data disappears once the instance ends or is cleaned up; a review can only look at Operate's history (and that is subject to a retention policy) | the file-based H2 database (`./data/hpas.mv.db`) keeps business data outside the process, so it survives restarts and instance completion (see the measurements in section 8) |
| **Cross-instance SQL reporting (management KPIs)** | variables are stored per instance and cannot be `GROUP BY`-ed or `SUM`-med; a few dozen instances could still be read by hand, a few hundred cannot | statements 8–12 of `demo-queries.sql`: completeness rate, funding-source distribution, 7-day letter compliance, enquiry-urgency distribution, payment-amount totals |
| **Uniqueness / idempotency** | variables have no unique constraint, so duplicate message delivery and job retries both produce duplicate data | `referral_id` / `appointment_id` / `case_id` / `psp_reference` / `idempotency_key` are unique; `process_audit` is idempotent on (instance, job, action) |
| **The business rule "one caseId may not have two successful payments"** | the engine cannot know that this caseId has already succeeded, so it relies on a human noticing | `PersistenceService.recordPayment` first queries `payment.case_id + status='success'`; on a hit it returns `duplicate-blocked:<existing pspReference>` and writes an audit row; the database additionally enforces `UNIQUE (case_id, status)` as a backstop |
| **Referential integrity** | variables carry no relationship constraints, so a "funding record pointing at an appointment that does not exist" is possible | the business key columns (`referral_id` / `appointment_id` / `case_id`) are aligned, and physical foreign keys can be added in the migration scripts |
| **An independent audit trail** | job retries execute repeatedly; Operate's log takes the engine's point of view and cannot be JOINed with the business tables | `process_audit` records `processInstanceKey / processDefinitionId / elementId / jobType / jobKey / performedBy / performedAt / action / businessKey / details`, so all operations on one patient can be strung together by business key |
| **Permission / role model** | `registeredBy` / `decidedBy` are only strings and cannot answer "who is authorised to perform a clinical review" | `app_user` + `user_role` (BR-01: a secretary must not perform a clinical assessment; BR-31: finance sees financial enquiries only) |

---

## 4. The 11 tables

| Table | Entity | Business meaning | Business key |
| --- | --- | --- | --- |
| `referral` | `Referral` | referral: patient, source, completeness outcome, missing items, current stage | `referral_id` unique |
| `patient` | `Patient` | patient master data (the name is currently used as `patient_ref`; a real patientId will replace it once HIS is integrated) | `patient_ref` unique |
| `appointment` | `Appointment` | appointment: specialty, priority, slot, confirmation time, notification channel and attempt count | `appointment_id` unique |
| `clinical_decision` | `ClinicalDecision` | clinical review decision (accept / reject / refer on / request supplement), its reason and the decision maker | `referral_id` unique |
| `clinic_letter` | `ClinicLetter` | letter: type, body, addressee, dispatch time, 7-day compliance, escalation outcome | `case_id` unique |
| `funding_case` | `FundingCase` | funding case: source, authorisation reference, approved amount, status, restrictions | `case_id` unique |
| `payment` | `Payment` | payment: amount / currency / method, PSP reference, status, idempotency key, failure reason | `psp_reference` / `idempotency_key` unique |
| `enquiry` | `Enquiry` | patient enquiry: type, content, urgency, triage note, routing status | `enquiry_id` unique |
| `app_user` | `AppUser` | application user (name, department, enabled flag) | `username` unique |
| `user_role` | `UserRole` | role / lane group (secretary, consultant, psp, gp-pool…) | (`username`,`role`,`group_name`) unique |
| `process_audit` | `ProcessAudit` | independent audit trail (what each job step did) | (`process_instance_key`,`job_key`,`action`) unique |

> The full set of columns was cross-checked against an `INFORMATION_SCHEMA.COLUMNS` export; see
> [ER-diagram.md](ER-diagram.md).

---

## 5. Process step -> table -> operation mapping (all 31 job types covered)

The write pattern is uniform: **read `job.getVariablesAsMap()` -> idempotent upsert through
`PersistenceService` -> write one `process_audit` row**.
Every database call is wrapped in try/catch, so **a database failure is only logged and never
affects the job result**.

| # | Process | Job type (topic unchanged) | Worker class | Table | Operation |
| --- | --- | --- | --- | --- | --- |
| 1 | P1 | `referral-completeness-check` | `ReferralWorkers` | `referral`, `process_audit` | update `completeness_status` / `missing_items` / `status`; audit `completeness-check` |
| 2 | P1 | `send-supplement-request` | `ReferralWorkers` | `referral`, `process_audit` | `status=SUPPLEMENT_REQUESTED`; audit `supplement-requested` |
| 3 | P1 | `notify-clinical-review` | `ReferralWorkers` | `referral`, `process_audit` | `status=UNDER_CLINICAL_REVIEW`; audit `notify-clinical-review` |
| 4 | P1 external | `gp-send-supplement` | `GpPoolWorkers` | `referral`, `process_audit` | clear `missing_items`, `completeness_status=SUPPLEMENT_RECEIVED`; audit `supplement-returned-by-gp` |
| 5 | P2 | `clinical-decision-audit` | `ClinicalReviewWorkers` | `clinical_decision`, `referral`, `process_audit` | upsert the decision (`decision` / `decided_by` / `reason`); referral status -> `DECIDED_*` |
| 6 | P2 | `notify-scheduling-team` | `ClinicalReviewWorkers` | `referral`, `process_audit` | audit `notify-scheduling-team` (publishes ReferralAccepted) |
| 7 | P2 | `notify-referral-institution` | `ClinicalReviewWorkers` | `referral`, `process_audit` | audit `notify-referral-institution` (publishes ReferralRejected) |
| 8 | P2 | `request-supplement` | `ClinicalReviewWorkers` | `referral`, `process_audit` | write `missing_items` (from `reviewForm.reason`); audit `supplement-requested-by-clinical-review` |
| 9 | P2 | `notify-other-hospital` | `ClinicalReviewWorkers` | `referral`, `process_audit` | audit `notify-other-hospital` (referral on) |
| 10 | P2 external | `partner-send-supplement` | `PartnerPoolWorkers` | `referral`, `process_audit` | supplement complete; audit `supplement-returned-by-partner` |
| 11 | P3 | `send-scheduling-request` | `SchedulingWorkers` | `appointment`, `process_audit` | upsert the appointment (`referral_id`); audit `upsert-appointment` |
| 12 | P3 | `notify-notification-team` | `SchedulingWorkers` | `appointment`, `process_audit` | write `appointment_date_time` / `confirmed_at` / `status=CONFIRMED` |
| 13 | P3 external | `scheduler-process-request` | `SchedulingPoolWorkers` | `appointment`, `process_audit` | write `slot_reference`; success = `PROPOSED`, failure = `PENDING_SLOT`; one `scheduler-attempt-N` audit row per attempt |
| 14 | P3 external | `scheduler-send-result` | `SchedulingPoolWorkers` | `appointment`, `process_audit` | persist the scheduling status before sending the reply |
| 15 | P4 | `communication-send` | `NotificationWorkers` | `appointment`, `process_audit` | write `notification_attempts` / `notification_channel`; on success -> `status=NOTIFIED` |
| 16 | P4 | `contact-log` | `NotificationWorkers` | `appointment`, `process_audit` | contact outcome (`reschedule` -> `RESCHEDULE_REQUESTED`); the audit includes `interpreterUsed` |
| 17 | P4 | `notify-confirm` | `NotificationWorkers` | `appointment`, `process_audit` | persist the patient-notified status |
| 18 | P10 | `letter-dispatch` | `LetterWorkers` | `clinic_letter`, `process_audit` | upsert the letter: `letter_type` / `body` / `addressee` / `dispatched_at` / `elapsed_days` / `dispatched_within7_days` |
| 19 | P10 | `notify-pathway-coordinator` | `LetterWorkers` | `clinic_letter`, `process_audit` | write the escalation fields `escalation_reason` / `escalation_decision` / `escalated_at` |
| 20 | P7 | `funding-validation` | `FundingWorkers` | `funding_case`, `process_audit` | upsert the funding case: `funding_source` / `approval_reference` / `approved_amount` / `restrictions` / `status` |
| 21 | P7 | `send-funding-request` | `FundingWorkers` | `funding_case`, `process_audit` | approval request sent (`status=pending`) |
| 22 | P7 external | `funder-send-result` | `FunderPoolWorkers` | `funding_case`, `process_audit` | receipt `approvalStatus=approved/rejected` -> `approved_at` |
| 23 | P12 | `send-payment-request` | `PaymentWorkers` | `payment`, `process_audit` | **pre-payment duplicate-charge check**: `hasSuccessfulPayment(caseId)`, audited as `alreadyHasSuccessfulPayment=` |
| 24 | P12 | `payment-retry` | `PaymentWorkers` | `process_audit` | the retry / cancel action only writes an audit row (`payment-retry-action`) and never creates a fake payment row |
| 25 | P12 | `notify-care-team` | `PaymentWorkers` | `payment`, `process_audit` | persist the final payment outcome (a duplicate success is blocked) |
| 26 | P12 external | `psp-process-payment` | `PspPoolWorkers` | `payment`, `process_audit` | write `psp_reference` / `status` / `failure_reason`; a duplicate success -> `duplicate-blocked` |
| 27 | P12 external | `psp-send-status` | `PspPoolWorkers` | `payment`, `process_audit` | persist before sending the reply (idempotency key `caseId|pspReference|status`) |
| 28 | P8 | `enquiry-triage` | `EnquiryWorkers` | `enquiry`, `process_audit` | upsert the enquiry: `inquiry_type` / `content` / `urgency` / `triage_note` / `urgent_keywords`, `status=TRIAGED` |
| 29 | P8 | `route-to-finance` | `EnquiryWorkers` | `enquiry`, `process_audit` | `status=ROUTED_FINANCE` |
| 30 | P8 | `await-finance-response` | `EnquiryWorkers` | `enquiry`, `process_audit` | `status=HANDLED_FINANCE` |
| 31 | P8 | `route-to-clinical` | `EnquiryWorkers` | `enquiry`, `process_audit` | `status=ROUTED_CLINICAL` (`urgency` is retained for the urgent highlight) |

**ADDITIVE guarantee**: the 31 `zeebe:taskDefinition` types, all output variable names and the
contents of the returned maps are **unchanged**; the only additions inside the worker methods are
"read variables -> write database" calls. The messages that are sent (`ReferralAccepted`,
`PaymentRequested`…) are completely unchanged as well.

---

## 6. Configuration (`src/main/resources/application.properties`)

```properties
# File-based H2: relative to the java directory -> java/data/hpas.mv.db; AUTO_SERVER allows several processes to connect at once
spring.datasource.url=jdbc:h2:file:./data/hpas;AUTO_SERVER=TRUE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# Create tables / add columns automatically from the entities at startup (for the demo; use Flyway/Liquibase in production)
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false

# H2 console: http://127.0.0.1:8090/h2-console
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# 8080 belongs to Camunda, so the demonstration API uses 8090
server.port=8090
```

**Not a single character of the Camunda configuration has changed** (`camunda.client.mode/grpc-address/rest-address`
are kept as they are).
To switch to MySQL / PostgreSQL, only replace `com.h2database:h2` with the matching driver and
change the four datasource lines above.

### Start / stop

```powershell
cd D:\process\process\java

# compile
D:\ruanjian\apache-maven-3.9.10\bin\mvn.cmd -B clean compile

# start (31 workers + demonstration API + automatic table creation)
D:\ruanjian\apache-maven-3.9.10\bin\mvn.cmd spring-boot:run

# start only the web layer / database without subscribing to jobs (when you just want to look at the tables or call the API)
D:\ruanjian\apache-maven-3.9.10\bin\mvn.cmd spring-boot:run "-Dspring-boot.run.arguments=--camunda.client.worker.defaults.enabled=false"
```

---

## 7. Demonstration API (read-only, port 8090)

| Endpoint | Purpose |
| --- | --- |
| `GET /api/db/summary` | **row count of every table** (the most direct evidence that the data really is in the database) |
| `GET /api/db/health` | database connectivity + the row counts of the three main tables |
| `GET /api/db/referrals` | referrals + the latest clinical decision |
| `GET /api/db/payments` | payment records (including `idempotencyKey` / `failureReason`) |
| `GET /api/db/audit` | audit trail (the most recent 200 rows) |
| `GET /api/db/audit/{businessKey}` | the complete trail for one business key (referralId / appointmentId / caseId / enquiryId) |
| `GET /api/db/chain/{businessKey}` | the whole chain of one business case (referral -> decision -> appointment -> funding -> letter -> payment -> audit) |
| `GET /api/db/appointments` | appointments (slot / confirmation time / notification channel and attempt count) |
| `GET /api/db/decisions` | clinical decisions |
| `GET /api/db/funding` | funding cases |
| `GET /api/db/letters` | letters (including 7-day compliance and escalation outcome) |
| `GET /api/db/enquiries` | enquiries (including urgency) |
| `GET /api/db/overview` | an overview of the IDs in the other business tables |

```powershell
# row-count overview
Invoke-RestMethod http://127.0.0.1:8090/api/db/summary | ConvertTo-Json -Depth 3

# payments: is a duplicate success blocked?
Invoke-RestMethod http://127.0.0.1:8090/api/db/payments | ConvertTo-Json -Depth 4

# the complete trail of one referral id
Invoke-RestMethod http://127.0.0.1:8090/api/db/audit/REF-DB-001 | ConvertTo-Json -Depth 4
```

---

## 8. Measured evidence (produced by real runs on this machine, not a design draft)

### 8.1 Table creation

Spring Boot startup log: `Found 11 JPA repository interfaces`, Hikari connects to
`jdbc:h2:file:./data/hpas`, and Hibernate creates **11 tables** (confirmed by an
`INFORMATION_SCHEMA.TABLES` query).

### 8.2 Real processes run end to end (no data inserted by hand)

The **existing BPMN files are deployed as they are** through the Camunda REST API (the files were
not modified in any way), and then instances are started:

| Scenario | Key command | Result |
| --- | --- | --- |
| P1 complete referral | `POST /v2/process-instances` `{referralId: REF-DB-001, ...}`, starting from `Task_CompletenessCheck` | the worker determines `isComplete=true`; `referral` gains 1 row (`completeness_status=COMPLETE`) and `process_audit` gains `completeness-check` |
| P12 first successful payment | `POST` `{caseId: CASE-DB-9001, amount: 1500.00}` | log `dbOutcome=stored:success`; `payment` has 1 row, `status=success`, `psp_reference=PSP-CASE-DB-9001-...` |
| P12 second success for the same caseId | start another instance in the same way (a different pspReference) | log `dbOutcome=duplicate-blocked:PSP-CASE-DB-9001-...`; **payment still has 1 row**; 3 audit rows `duplicate-payment-blocked` |
| P3 scheduling (with timer retry) | `SIMULATE_SCHEDULING_FAIL_TIMES=1`, starting from `Throw_SchedulingRequest` | the first failure writes the audit row `scheduler-attempt-1` and the appointment row is created (`appointment_id=APPT-REF-DB-0002`, with `referral_id` correctly back-filled) |
| P7 funding (external pool approval) | a leftover pending `funding-validation` job on this machine was consumed | `funding_case` gains `CASE-RBAC-3003` (`appointment_id=APPT-RBAC-3002`, `approved_amount=250.00`) + audit rows |

No run produced an `ERROR`, an `Exception` or an incident.

### 8.3 The data is still there after a restart

```
17:09:00  application stopped (Tomcat/8090 shut down, H2 file released)
17:13:42  application started again
17:14:21  GET /api/db/summary  →  referral=1  appointment=1  funding_case=1  payment=1
                                   process_audit=12  app_user=7  user_role=8   (31 rows in total)
```

Database file: `java/data/hpas.mv.db` (H2 2.4.240).

### 8.4 Every statement in `demo-queries.sql` runs successfully

Executed one by one with the Shell that ships with H2:

```powershell
java -cp C:\Users\<you>\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar `
     org.h2.tools.Shell `
     -url "jdbc:h2:file:D:/process/process/java/data/hpas;AUTO_SERVER=TRUE" `
     -user sa -sql "<one statement>"
```

| Statistic | Value |
| --- | --- |
| Statements in total | **14** |
| Executed successfully | **14** (0 syntax / runtime errors) |

Typical output (measured on 2026-10-13):

```
1) rows per table: referral=1, appointment=1, funding_case=1, payment=1,
              process_audit=12, app_user=7, user_role=8        (31 rows in total)
5) payment: CASE-DB-9001 | 1500.00 | CNY | credit-card | PSP-CASE-DB-9001-1791882534442 | success
6) duplicate-charge check (two successful payments for one caseId): (0 rows)   <- the constraint is working
7) duplicate-payment-blocked audit rows: 3 rows
9) KPI: CNY | payments=1 | total_amount=1500.00
```

(The `appointment=1` row comes from that P3 demonstration instance; its status is still stopped at
the intermediate state waiting for a user task, so `status` is `NULL`.)

> Note: run the H2 Shell with `java -Dstdout.encoding=UTF-8` so that Chinese text inside `details`
> is not garbled on the console (the early audit rows were written from a console using the default
> GBK encoding, which is a display issue only; newer data displays correctly with
> `-Dstdout.encoding=UTF-8`).

---

## 9. Design trade-offs

1. **A database write never throws**: every public method of `PersistenceService` uses try/catch +
   logging. The goal is "if the database breaks, the process still runs", and a job must never
   become an incident — consistent with the "ADDITIVE" requirement of the task.
2. **Idempotency first**: every upsert is keyed on the business key, and `process_audit`
   de-duplicates on (instance, job, action), so job retries never pollute the reports.
3. **No physical foreign keys**: the process may reach P7 before the P1 master data is supplied, and
   string business keys fit Camunda's correlation-key model better; where a hard constraint is
   needed, add a `FOREIGN KEY` in the migration scripts (the column names are already aligned).
4. **`ddl-auto=update` is for the course demonstration only**: in production it should become
   `validate` plus Flyway/Liquibase migration scripts.
5. **Column width for Chinese text**: H2's `VARCHAR(2000)` counts **characters**, not bytes, so
   Chinese text is not truncated (a measured 108-character audit detail was stored in full and
   displays correctly when read as UTF-8).
