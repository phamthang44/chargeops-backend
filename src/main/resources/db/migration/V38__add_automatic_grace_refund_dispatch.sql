-- Automatic first execution attempt for deterministic grace-period refunds.
-- Dispatch retries are technical delivery retries only; a terminal FAILED attempt
-- is processed once and requires explicit Admin action.

ALTER TABLE refunds
    ADD COLUMN execution_policy varchar(30),
    ADD COLUMN requires_admin_action boolean;

UPDATE refunds
   SET execution_policy = CASE
           WHEN reason = 'VOLUNTARY_GRACE' THEN 'AUTO_FIRST_ATTEMPT'
           ELSE 'ADMIN_REQUIRED'
       END,
       requires_admin_action = CASE
           WHEN reason = 'VOLUNTARY_GRACE' THEN false
           ELSE true
       END;

ALTER TABLE refunds
    ALTER COLUMN execution_policy SET NOT NULL,
    ALTER COLUMN requires_admin_action SET NOT NULL,
    ADD CONSTRAINT ck_refunds_execution_policy
        CHECK (execution_policy IN ('AUTO_FIRST_ATTEMPT', 'ADMIN_REQUIRED'));

ALTER TABLE refund_attempts
    ADD COLUMN execution_trigger varchar(30);

UPDATE refund_attempts SET execution_trigger = 'ADMIN';

ALTER TABLE refund_attempts
    ALTER COLUMN execution_trigger SET NOT NULL,
    ALTER COLUMN performed_by DROP NOT NULL,
    ADD CONSTRAINT ck_refund_attempts_execution_trigger
        CHECK (execution_trigger IN ('SYSTEM_POLICY', 'ADMIN')),
    ADD CONSTRAINT ck_refund_attempts_actor_by_trigger CHECK (
        (execution_trigger = 'ADMIN' AND performed_by IS NOT NULL)
        OR (execution_trigger = 'SYSTEM_POLICY' AND performed_by IS NULL)
    );

CREATE TABLE refund_auto_dispatches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id uuid NOT NULL,
    request_key uuid NOT NULL,
    status varchar(20) NOT NULL,
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,

    CONSTRAINT fk_refund_auto_dispatches_refund
        FOREIGN KEY (refund_id) REFERENCES refunds(id) ON DELETE RESTRICT,
    CONSTRAINT ux_refund_auto_dispatches_refund UNIQUE (refund_id),
    CONSTRAINT ck_refund_auto_dispatches_status
        CHECK (status IN ('PENDING', 'PROCESSED')),
    CONSTRAINT ck_refund_auto_dispatches_completion CHECK (
        (status = 'PENDING' AND processed_at IS NULL)
        OR (status = 'PROCESSED' AND processed_at IS NOT NULL)
    )
);

CREATE INDEX idx_refund_auto_dispatches_status_created
    ON refund_auto_dispatches(status, created_at, id);

-- Existing pending grace obligations are safely queued exactly once. The persisted
-- key remains stable for every technical replay of this dispatch.
INSERT INTO refund_auto_dispatches (refund_id, request_key, status)
SELECT r.id, gen_random_uuid(), 'PENDING'
  FROM refunds r
 WHERE r.reason = 'VOLUNTARY_GRACE'
   AND r.status = 'PENDING'
ON CONFLICT (refund_id) DO NOTHING;

COMMENT ON TABLE refund_auto_dispatches IS
    'Durable one-shot dispatch for the first automatic VOLUNTARY_GRACE refund attempt.';
