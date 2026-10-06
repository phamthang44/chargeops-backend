-- BKG-061: run with psql -X -v ON_ERROR_STOP=1 -f this-file.sql.
-- Capture output separately for EACH target environment. Never infer remote
-- emptiness from local/test evidence. No credentials or account numbers emitted.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_database() AS database_name, current_schema() AS schema_name,
       version() AS postgres_version, now() AS captured_at;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT 'owner_payouts' AS table_name, environment, status, count(*) AS rows, sum(amount) AS amount_sum
FROM owner_payouts GROUP BY environment, status
UNION ALL SELECT 'owner_payout_items', environment, status, count(*), sum(amount)
FROM owner_payout_items GROUP BY environment, status
UNION ALL SELECT 'payout_attempts', environment, status, count(*), sum(amount)
FROM payout_attempts GROUP BY environment, status
UNION ALL SELECT 'owner_adjustments', environment, reason, count(*), sum(amount)
FROM owner_adjustments GROUP BY environment, reason ORDER BY 1,2,3;
SELECT 'owner_payouts' AS table_name, count(*) AS total_rows FROM owner_payouts
UNION ALL SELECT 'owner_payout_items', count(*) FROM owner_payout_items
UNION ALL SELECT 'payout_attempts', count(*) FROM payout_attempts
UNION ALL SELECT 'owner_adjustments', count(*) FROM owner_adjustments;
SELECT conrelid::regclass AS source_table, confrelid::regclass AS target_table,
       conname, pg_get_constraintdef(oid) AS definition FROM pg_constraint
WHERE contype='f' AND (conrelid IN ('owner_payouts'::regclass,'owner_payout_items'::regclass,
    'payout_attempts'::regclass,'owner_adjustments'::regclass)
 OR confrelid IN ('owner_payouts'::regclass,'owner_payout_items'::regclass,
    'payout_attempts'::regclass,'owner_adjustments'::regclass)) ORDER BY 1,3;
SELECT tgrelid::regclass AS table_name, tgname, pg_get_triggerdef(oid) AS definition
FROM pg_trigger WHERE NOT tgisinternal AND tgrelid IN
('owner_payouts'::regclass,'owner_payout_items'::regclass,'payout_attempts'::regclass,'owner_adjustments'::regclass)
ORDER BY 1,2;
SELECT DISTINCT v.oid::regclass AS dependent_view, t.oid::regclass AS source_table
FROM pg_depend d JOIN pg_rewrite r ON r.oid=d.objid
JOIN pg_class v ON v.oid=r.ev_class JOIN pg_class t ON t.oid=d.refobjid
WHERE v.relkind IN ('v','m') AND t.oid IN
('owner_payouts'::regclass,'owner_payout_items'::regclass,'payout_attempts'::regclass,'owner_adjustments'::regclass)
ORDER BY 1,2;
SELECT schemaname, viewname, definition FROM pg_views
WHERE definition ~ '(owner_payouts|owner_payout_items|payout_attempts|owner_adjustments)';
SELECT count(*) AS bookings, count(financial_owner_id) AS attributed_bookings FROM bookings;
COMMIT;
