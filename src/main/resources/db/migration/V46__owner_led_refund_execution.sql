-- Owner-authorized refunds receive the first Simulator attempt automatically.
-- Historical Admin attempts remain immutable evidence. Pending obligations with
-- no attempt can be safely dispatched once; failed attempts go to Owner retry.
ALTER TABLE refunds DROP CONSTRAINT IF EXISTS ck_refunds_execution_policy;

UPDATE refunds
   SET execution_policy = 'AUTO_FIRST_ATTEMPT',
       requires_admin_action = CASE
           WHEN status = 'PENDING' AND EXISTS (
               SELECT 1 FROM refund_attempts a
                WHERE a.refund_id = refunds.id AND a.status = 'FAILED'
           ) THEN true
           ELSE false
       END
 WHERE status = 'PENDING'
   AND payment_id IN (SELECT id FROM payments WHERE environment = 'SIMULATOR');

ALTER TABLE refunds
    ADD CONSTRAINT ck_refunds_execution_policy
        CHECK (execution_policy IN ('AUTO_FIRST_ATTEMPT', 'ADMIN_REQUIRED'));

INSERT INTO refund_auto_dispatches (refund_id, request_key, status)
SELECT r.id, gen_random_uuid(), 'PENDING'
  FROM refunds r
 WHERE r.status = 'PENDING'
   AND r.payment_id IN (SELECT id FROM payments WHERE environment = 'SIMULATOR')
   AND NOT EXISTS (SELECT 1 FROM refund_attempts a WHERE a.refund_id = r.id)
ON CONFLICT (refund_id) DO NOTHING;

-- Stop any historical dispatch created for a non-Simulator payment. Its
-- financial resolution remains outside the demo and must not fabricate money.
UPDATE refund_auto_dispatches d
   SET status = 'PROCESSED', processed_at = now(), updated_at = now()
 WHERE d.status = 'PENDING'
   AND EXISTS (
       SELECT 1 FROM refunds r JOIN payments p ON p.id = r.payment_id
        WHERE r.id = d.refund_id AND p.environment <> 'SIMULATOR'
   );

ALTER TABLE refund_attempts DROP CONSTRAINT IF EXISTS ck_refund_attempts_execution_trigger;
ALTER TABLE refund_attempts DROP CONSTRAINT IF EXISTS ck_refund_attempts_actor_by_trigger;
ALTER TABLE refund_attempts
    ADD CONSTRAINT ck_refund_attempts_execution_trigger
        CHECK (execution_trigger IN ('SYSTEM_POLICY', 'OWNER', 'ADMIN')),
    ADD CONSTRAINT ck_refund_attempts_actor_by_trigger CHECK (
        (execution_trigger IN ('OWNER', 'ADMIN') AND performed_by IS NOT NULL)
        OR (execution_trigger = 'SYSTEM_POLICY' AND performed_by IS NULL)
    );

COMMENT ON COLUMN refunds.requires_admin_action IS
    'Legacy column: pending refund needs Owner retry after a failed Simulator attempt; Admin no longer executes routine refunds.';
COMMENT ON TABLE refund_auto_dispatches IS
    'Durable one-shot dispatch for the first Simulator attempt of an approved full-package refund.';
