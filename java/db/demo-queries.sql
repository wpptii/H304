-- =====================================================================
--  demo-queries.sql  --  HPAS persistence demonstration queries
-- ---------------------------------------------------------------------
--  Target database: file-based H2 (tables created automatically when Spring Boot starts)
--      jdbc:h2:file:./data/hpas;AUTO_SERVER=TRUE     user sa, empty password
--
--  How to run (every statement in this file has actually been run this way):
--      java -cp <h2.jar> org.h2.tools.Shell ^
--           -url "jdbc:h2:file:D:/process/process/java/data/hpas;AUTO_SERVER=TRUE" ^
--           -user sa -sql "<one statement>"
--
--      example h2.jar path:
--      C:\Users\<you>\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar
--
--  Note: do not put USER=/PASSWORD= in the URL, otherwise H2 reports "Duplicate property USER";
--        the user name must be passed separately with -user sa.
--
--  Note: this file contains SELECT statements only and never changes data; the final
--        "duplicate charge" question asks whether two successful payments exist, and 0 rows means the constraint is working.
--
--  Measured: all 14 statements execute successfully (0 syntax / runtime errors); the "measured values"
--  below are the real output of the 2026-10-13 run (the numbers grow on another machine or after running the processes again).
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1) Row count of every table: the process instance finished long ago, yet the data is still here
--    measured values: referral=1, appointment=1, funding_case=1, payment=1, process_audit=12,
--            app_user=7, user_role=8, the rest 0 (31 rows in total)
-- ---------------------------------------------------------------------
SELECT 'referral'          AS table_name, COUNT(*) AS rows FROM referral
UNION ALL SELECT 'patient',           COUNT(*) FROM patient
UNION ALL SELECT 'appointment',       COUNT(*) FROM appointment
UNION ALL SELECT 'clinical_decision', COUNT(*) FROM clinical_decision
UNION ALL SELECT 'clinic_letter',     COUNT(*) FROM clinic_letter
UNION ALL SELECT 'funding_case',      COUNT(*) FROM funding_case
UNION ALL SELECT 'payment',           COUNT(*) FROM payment
UNION ALL SELECT 'enquiry',           COUNT(*) FROM enquiry
UNION ALL SELECT 'process_audit',     COUNT(*) FROM process_audit
UNION ALL SELECT 'app_user',          COUNT(*) FROM app_user
UNION ALL SELECT 'user_role',         COUNT(*) FROM user_role;


-- ---------------------------------------------------------------------
-- 2) The full chain of one case: referral -> clinical decision -> appointment -> funding -> letter -> payment
--    Uses LEFT JOIN, so instances that have not reached every stage are still listed (right-hand columns are NULL)
-- ---------------------------------------------------------------------
SELECT r.referral_id,
       r.patient_name,
       r.completeness_status,
       r.status               AS referral_status,
       d.decision,
       d.decided_by,
       a.appointment_id,
       a.slot_reference,
       a.appointment_date_time,
       f.case_id              AS funding_case_id,
       f.funding_source,
       f.approved_amount,
       l.case_id              AS letter_case_id,
       l.status               AS letter_status,
       l.dispatched_within7_days,
       p.psp_reference,
       p.status               AS payment_status,
       p.amount               AS paid_amount
FROM referral r
LEFT JOIN clinical_decision d ON d.referral_id = r.referral_id
LEFT JOIN appointment       a ON a.referral_id = r.referral_id
LEFT JOIN funding_case      f ON f.referral_id = r.referral_id
LEFT JOIN clinic_letter     l ON l.case_id     = f.case_id
LEFT JOIN payment           p ON p.case_id     = f.case_id
ORDER BY r.id;


-- ---------------------------------------------------------------------
-- 3) Audit trail: who did what on which process instance / job
--    (a job retry never writes a duplicate row: idempotent on process_instance_key + job_key + action)
-- ---------------------------------------------------------------------
SELECT id,
       job_type,
       action,
       business_key,
       process_definition_id,
       element_id,
       process_instance_key,
       job_key,
       performed_by,
       performed_at,
       details
FROM process_audit
ORDER BY id;


-- ---------------------------------------------------------------------
-- 4) The complete trail of one business key (example: referral id REF-DB-001)
-- ---------------------------------------------------------------------
SELECT performed_at, action, job_type, process_instance_key, details
FROM process_audit
WHERE business_key = 'REF-DB-001'
ORDER BY id;


-- ---------------------------------------------------------------------
-- 5) Payment details + idempotency key (a repeated PSP reference arriving again leaves only one row)
-- ---------------------------------------------------------------------
SELECT case_id, amount, currency, method, psp_reference, status,
       idempotency_key, failure_reason, paid_at
FROM payment
ORDER BY id;


-- ---------------------------------------------------------------------
-- 6) Duplicate-charge check (business rule): does one caseId have two successful payments?
--    the normal result is 0 rows (the UNIQUE (case_id, status) constraint is the backstop)
-- ---------------------------------------------------------------------
SELECT case_id, COUNT(*) AS successful_payment_rows
FROM payment
WHERE status = 'success'
GROUP BY case_id
HAVING COUNT(*) > 1;


-- ---------------------------------------------------------------------
-- 7) Duplicate-charge check (another angle): the second successful payment that was blocked
--    these payments never enter the payment table, they appear in the audit trail only
-- ---------------------------------------------------------------------
SELECT performed_at, job_type, business_key, details
FROM process_audit
WHERE action = 'duplicate-payment-blocked'
ORDER BY id;


-- ---------------------------------------------------------------------
-- 8) Management KPIs: SQL reporting across process instances (computed entirely in the database, with no reliance on Operate)
--    referral completeness rate / average funding amount
-- ---------------------------------------------------------------------
SELECT COUNT(*)                                                   AS referrals,
       SUM(CASE WHEN completeness_status = 'COMPLETE' THEN 1 ELSE 0 END) AS complete_referrals,
       SUM(CASE WHEN completeness_status = 'INCOMPLETE' THEN 1 ELSE 0 END) AS incomplete_referrals
FROM referral;

SELECT currency,
       COUNT(*)        AS payments,
       SUM(amount)     AS total_amount,
       AVG(amount)     AS avg_amount
FROM payment
WHERE status = 'success'
GROUP BY currency;


-- ---------------------------------------------------------------------
-- 9) Management KPIs: funding-source distribution + approved amounts
-- ---------------------------------------------------------------------
SELECT funding_source,
       status,
       COUNT(*)             AS cases,
       SUM(approved_amount) AS approved_total
FROM funding_case
GROUP BY funding_source, status
ORDER BY cases DESC;


-- ---------------------------------------------------------------------
-- 10) P10 letters: compliance with the 7-day deadline (BR-27)
-- ---------------------------------------------------------------------
SELECT COUNT(*)                                                        AS letters,
       SUM(CASE WHEN dispatched_within7_days = TRUE THEN 1 ELSE 0 END)  AS within_7_days,
       SUM(CASE WHEN dispatched_within7_days = FALSE THEN 1 ELSE 0 END) AS over_deadline
FROM clinic_letter;


-- ---------------------------------------------------------------------
-- 11) P8 enquiry urgency distribution (those matching an urgent keyword are highlighted)
-- ---------------------------------------------------------------------
SELECT urgency, status, COUNT(*) AS enquiries
FROM enquiry
GROUP BY urgency, status
ORDER BY enquiries DESC;


-- ---------------------------------------------------------------------
-- 12) Role model: who belongs to which pool (the evidence for BR-01, a secretary must not perform a clinical assessment)
-- ---------------------------------------------------------------------
SELECT u.username, u.display_name, u.department, r.role, r.group_name
FROM app_user u
LEFT JOIN user_role r ON r.username = u.username
ORDER BY u.username, r.role;


-- ---------------------------------------------------------------------
-- 13) Appointment notification status (P4): attempt count and channel
-- ---------------------------------------------------------------------
SELECT appointment_id, referral_id, slot_reference, appointment_date_time,
       status, notification_channel, notification_attempts, confirmed_at
FROM appointment
ORDER BY id;
