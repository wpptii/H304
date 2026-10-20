# Entity / relationship description (ER) — the HPAS persistence layer

> This document describes the **11 JPA entities** in `hpas.db.domain`, the 11 physical tables they
> correspond to, and the relationship between process variables and database columns.
>
> ⚠️ Every field name below **comes from the tables that were actually created** (cross-checked
> against an `INFORMATION_SCHEMA.COLUMNS` export), not from a design draft. The column names are
> exactly the names that `demo-queries.sql` can query directly.

## 0. The whole picture in one diagram

```mermaid
erDiagram
    REFERRAL ||--o| PATIENT : "patient_name = patient_ref"
    REFERRAL ||--o| CLINICAL_DECISION : "referral_id"
    REFERRAL ||--o{ APPOINTMENT : "referral_id"
    REFERRAL ||--o{ FUNDING_CASE : "referral_id"
    APPOINTMENT ||--o{ FUNDING_CASE : "appointment_id"
    FUNDING_CASE ||--o| CLINIC_LETTER : "case_id"
    FUNDING_CASE ||--o{ PAYMENT : "case_id"
    APP_USER ||--o{ USER_ROLE : "username"
    PROCESS_AUDIT }o..o{ REFERRAL : "business_key = referral_id"
    PROCESS_AUDIT }o..o{ APPOINTMENT : "business_key = appointment_id"
    PROCESS_AUDIT }o..o{ FUNDING_CASE : "business_key = case_id"
    PROCESS_AUDIT }o..o{ PAYMENT : "business_key = case_id"
    PROCESS_AUDIT }o..o{ ENQUIRY : "business_key = enquiry_id"

    REFERRAL {
        BIGINT id PK
        VARCHAR referral_id UK "BPMN: referralId"
        VARCHAR patient_name "BPMN: patientName"
        DATE patient_dob "BPMN: patientDob (optional)"
        VARCHAR referral_source "BPMN: referralSource"
        DATE received_date
        VARCHAR completeness_status "isComplete -> COMPLETE/INCOMPLETE"
        VARCHAR missing_items "BPMN: missingItems"
        VARCHAR status
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    PATIENT {
        BIGINT id PK
        VARCHAR patient_ref UK "= patient_name"
        VARCHAR full_name
        DATE date_of_birth
        TIMESTAMPTZ created_at
    }
    APPOINTMENT {
        BIGINT id PK
        VARCHAR appointment_id UK "BPMN: appointmentId"
        VARCHAR referral_id FK "BPMN: referralId"
        VARCHAR specialty
        VARCHAR priority "BPMN: priority"
        TIMESTAMP appointment_date_time "BPMN: appointmentDateTime"
        VARCHAR slot_reference "BPMN: slotReference"
        VARCHAR status
        TIMESTAMPTZ confirmed_at
        VARCHAR notification_channel "BPMN: notifyChannel"
        INTEGER notification_attempts "BPMN: notifyAttempts"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    CLINICAL_DECISION {
        BIGINT id PK
        VARCHAR referral_id FK_UK "BPMN: referralId"
        VARCHAR decided_by "BPMN: decidedBy"
        VARCHAR decision "BPMN: decision"
        VARCHAR reason "BPMN: reason"
        TIMESTAMPTZ decided_at
    }
    CLINIC_LETTER {
        BIGINT id PK
        VARCHAR case_id UK "BPMN: caseId"
        VARCHAR patient_name
        VARCHAR letter_type "BPMN: letterType"
        VARCHAR body "BPMN: letterBody"
        VARCHAR addressee "BPMN: addressee"
        TIMESTAMPTZ dispatched_at "BPMN: dispatchedAt"
        VARCHAR status
        INTEGER elapsed_days "BPMN: elapsedDays"
        BOOLEAN dispatched_within7_days "BPMN: dispatchedWithin7Days"
        VARCHAR escalation_reason "BPMN: escalationReason"
        VARCHAR escalation_decision "BPMN: escalationDecision"
        TIMESTAMPTZ escalated_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    FUNDING_CASE {
        BIGINT id PK
        VARCHAR case_id UK "BPMN: caseId"
        VARCHAR referral_id FK
        VARCHAR appointment_id FK "BPMN: appointmentId"
        VARCHAR funding_source "BPMN: fundingSource"
        VARCHAR approval_reference "BPMN: approvalReference/authorizationRef"
        NUMERIC approved_amount "BPMN: approvedAmount"
        VARCHAR status "BPMN: approvalStatus"
        TIMESTAMPTZ approved_at
        VARCHAR restrictions "BPMN: restrictions"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    PAYMENT {
        BIGINT id PK
        VARCHAR case_id FK "BPMN: caseId"
        NUMERIC amount "BPMN: amount"
        VARCHAR currency
        VARCHAR method "BPMN: paymentMethod"
        VARCHAR psp_reference UK "BPMN: pspReference"
        VARCHAR status "BPMN: paymentStatus"
        VARCHAR idempotency_key UK
        VARCHAR failure_reason "BPMN: failureReason"
        TIMESTAMPTZ paid_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    ENQUIRY {
        BIGINT id PK
        VARCHAR enquiry_id UK "BPMN: enquiryId"
        VARCHAR inquiry_type "BPMN: inquiryType"
        VARCHAR content "BPMN: content"
        VARCHAR patient_name
        VARCHAR urgency "BPMN: triageUrgency"
        VARCHAR triage_note "BPMN: triageNote"
        VARCHAR urgent_keywords "BPMN: urgentKeywords"
        VARCHAR status
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    APP_USER {
        BIGINT id PK
        VARCHAR username UK
        VARCHAR password_hash
        VARCHAR display_name
        VARCHAR department
        BOOLEAN enabled
        TIMESTAMPTZ created_at
    }
    USER_ROLE {
        BIGINT id PK
        VARCHAR username FK
        VARCHAR role
        VARCHAR group_name
        TIMESTAMPTZ granted_at
    }
    PROCESS_AUDIT {
        BIGINT id PK
        BIGINT process_instance_key
        VARCHAR process_definition_id
        BIGINT process_definition_key
        VARCHAR element_id
        VARCHAR job_type
        BIGINT job_key
        VARCHAR performed_by
        TIMESTAMPTZ performed_at
        VARCHAR action
        VARCHAR business_key
        VARCHAR details
    }
```

## 1. Relationships (and why they are this way)

| Relationship | Cardinality | Basis |
| --- | --- | --- |
| `referral` → `clinical_decision` | 1 : 0..1 | a referral keeps only the **latest** clinical decision (`clinical_decision.referral_id` is unique) |
| `referral` → `appointment` | 1 : 0..n | P3 scheduling may be retried; the current worker uses `appointmentId` (deriving `APPT-<referralId>` when it is missing) so that one appointment is always one row |
| `referral` → `funding_case` | 1 : 0..n | funding cases may be added in P7; the business key is `caseId` (P7 uses appointmentId, P10/P12 use caseId) |
| `appointment` → `funding_case` | 1 : 0..n | P7 correlates on `appointmentId` (the correlationKey of `FundingApprovalResult`) |
| `funding_case` → `payment` | 1 : 0..n | **business rule: 1 : 0..1 rows with `status='success'`** (one caseId may not have two successful payments) |
| `funding_case` → `clinic_letter` | 1 : 0..1 | P10 uses `caseId` |
| `app_user` → `user_role` | 1 : 0..n | the database counterpart of Camunda's role/group (the data behind BR-01 / BR-31) |
| `process_audit` → all business tables | n : 1 (weak association) | associates business keys through `business_key` (referralId / appointmentId / caseId / enquiryId) with no physical foreign key, so that "the audit row can still be written when the business-table write fails" |

> Note: these relationships are **expressed at the JPA level with business keys (strings)** rather
> than with a physical `@ManyToOne` foreign-key constraint. The reason: on the Camunda side the
> correlation key is a string business key (`referralId` / `appointmentId` / `caseId`), and the
> process may reach P7 before the P1 master data is supplied. Where a hard foreign key is needed,
> simply add `FOREIGN KEY` in the production (`ddl-auto=validate`) migration scripts — the column
> names are already aligned.

## 2. Unique / idempotency constraints (hard guarantees at the database level)

| Table | Constraint | Effect |
| --- | --- | --- |
| `referral` | `referral_id` unique | one referral id is one row (duplicate delivery of process variables cannot create duplicate master data) |
| `appointment` | `appointment_id` unique | as above |
| `clinical_decision` | `referral_id` unique | one referral has exactly one latest decision |
| `clinic_letter` | `case_id` unique | one letter case is one row |
| `funding_case` | `case_id` unique | one funding case is one row |
| `payment` | `psp_reference` unique | a PSP reference can never be posted twice |
| `payment` | `idempotency_key` unique | the same `caseId + pspReference + status` produces exactly one row |
| `payment` | `uq_payment_case_status` UNIQUE (`case_id`,`status`) | the same outcome for one caseId can only be one row; together with the business check this delivers "one successful payment per case" |
| `enquiry` | `enquiry_id` unique | an enquiry is exactly one row |
| `app_user` | `username` unique | the account is unique |
| `user_role` | UNIQUE (`username`,`role`,`group_name`) | authorisations are not duplicated |
| `process_audit` | UNIQUE (`process_instance_key`,`job_key`,`action`) | **a job retry never writes a duplicate audit row** (idempotent) |

The backstop effect of the `UNIQUE` constraints that the database actually enforces can be checked
directly with statement 6 of `demo-queries.sql`: the normal result is **0 rows** (no caseId has two
successful payments).

## 3. Process variables ↔ database columns

- every `@JobWorker` still only reads `job.getVariablesAsMap()` and **writes back no new variables**;
- the mapping between column names and BPMN variable names is given by the `"BPMN: xxx"` comments
  in each table above;
- when an optional variable is missing, `NULL` is always written (for example `patientDob`, which
  the current form does not collect), and the process behaviour is exactly as it was before the
  database was added.
