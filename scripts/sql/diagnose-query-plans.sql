-- Run with psql against the intended database. No data or schema changes.
-- Demo-sized data cannot establish performance at production scale.
\set ON_ERROR_STOP on
\pset pager off
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';

\echo === Booking expiration candidates (default batch 100) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, connector_id FROM bookings
WHERE status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP
ORDER BY expires_at, id LIMIT 100;

\echo === Booking no-show candidates (default batch 50) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, connector_id FROM bookings
WHERE status = 'CONFIRMED' AND check_in_deadline IS NOT NULL
  AND check_in_deadline <= CURRENT_TIMESTAMP
ORDER BY check_in_deadline, id LIMIT 50;

\echo === Booking completion candidates (default batch 50) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, connector_id FROM bookings
WHERE status IN ('CHECKED_IN', 'CHARGING') AND end_at <= CURRENT_TIMESTAMP
ORDER BY end_at, id LIMIT 50;

-- Read-only approximation of candidate selection only.
-- Deliberately omits FOR UPDATE SKIP LOCKED and the claim UPDATE.
-- These plans do not measure claim writes, row locking, or worker concurrency.
\echo === Outbox candidate selection ONLY ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT event_id FROM notification_outbox
WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
   OR (status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP)
ORDER BY next_attempt_at, occurred_at, event_id LIMIT 1;

\echo === Email candidate selection ONLY (example maxAttempts 5) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT delivery_id FROM notification_email_deliveries
WHERE attempt_count < 5 AND send_started_at IS NULL
  AND ((status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
    OR (status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP))
ORDER BY next_attempt_at, created_at, delivery_id LIMIT 1;

-- Choose a real financial owner without printing their identifier.
SELECT financial_owner_id AS owner_id FROM bookings
WHERE financial_owner_id IS NOT NULL
GROUP BY financial_owner_id ORDER BY count(*) DESC, financial_owner_id LIMIT 1
\gset
\if :{?owner_id}
\echo === Owner finance daily totals (30 days, all stations) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT (occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
       SUM(gross_vnd) AS gross, SUM(refunded_vnd) AS refunded
FROM v_owner_simulator_cash_events
WHERE owner_id = :'owner_id'::uuid
  AND occurred_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
  AND occurred_at < CURRENT_TIMESTAMP AND occurred_at <= CURRENT_TIMESTAMP
GROUP BY day ORDER BY day;
\else
\echo No financial owner found; finance plan skipped.
\endif
ROLLBACK;
