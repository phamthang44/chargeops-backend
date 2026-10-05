-- BKG-059. Payout records are an internal financial history, not a bank integration.

-- Existing booking ownership is reconstructed from its station once at rollout.
-- New bookings capture the beneficiary at creation, so a later station ownership
-- change cannot reassign historical booking finance to another Owner.
ALTER TABLE bookings ADD COLUMN financial_owner_id uuid;
UPDATE bookings b SET financial_owner_id = s.owner_id
  FROM connectors c JOIN charge_points cp ON cp.id = c.charge_point_id
       JOIN stations s ON s.id = cp.station_id
 WHERE b.connector_id = c.id;
ALTER TABLE bookings
    ALTER COLUMN financial_owner_id SET NOT NULL,
    ADD CONSTRAINT fk_bookings_financial_owner
        FOREIGN KEY (financial_owner_id) REFERENCES user_profile(id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION capture_booking_financial_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT s.owner_id INTO NEW.financial_owner_id
          FROM connectors c JOIN charge_points cp ON cp.id = c.charge_point_id
               JOIN stations s ON s.id = cp.station_id
         WHERE c.id = NEW.connector_id;
        IF NEW.financial_owner_id IS NULL THEN
            RAISE EXCEPTION 'booking financial owner cannot be determined' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.financial_owner_id IS DISTINCT FROM OLD.financial_owner_id THEN
        RAISE EXCEPTION 'booking financial owner is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_booking_financial_owner
    BEFORE INSERT OR UPDATE OF financial_owner_id ON bookings
    FOR EACH ROW EXECUTE FUNCTION capture_booking_financial_owner();

CREATE TABLE owner_payouts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_code varchar(32) NOT NULL UNIQUE,
    owner_id uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    amount numeric(19,2) NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'VND',
    environment varchar(10) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    successful_attempt_id uuid,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ux_owner_payouts_id_environment UNIQUE (id, environment),
    CONSTRAINT ck_owner_payouts_code CHECK (payout_code ~ '^PO-[0-9]{8}-[0-9]{4,}$'),
    CONSTRAINT ck_owner_payouts_amount CHECK (amount > 0 AND amount <> 'NaN'::numeric AND amount = trunc(amount)),
    CONSTRAINT ck_owner_payouts_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_owner_payouts_environment CHECK (environment IN ('SIMULATOR', 'TEST', 'LIVE')),
    CONSTRAINT ck_owner_payouts_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'CANCELLED')),
    CONSTRAINT ck_owner_payouts_period CHECK (period_end > period_start),
    CONSTRAINT ck_owner_payouts_version CHECK (version >= 0),
    CONSTRAINT ck_owner_payouts_completion CHECK (
        (status = 'SUCCEEDED' AND successful_attempt_id IS NOT NULL AND completed_at IS NOT NULL)
        OR (status IN ('PENDING', 'CANCELLED') AND successful_attempt_id IS NULL AND completed_at IS NULL)
    )
);
CREATE INDEX idx_owner_payouts_owner_status_environment
    ON owner_payouts(owner_id, status, environment, created_at DESC);

CREATE TABLE owner_payout_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id uuid NOT NULL,
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE RESTRICT,
    payment_id uuid NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    amount numeric(19,2) NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'VND',
    environment varchar(10) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT fk_owner_payout_items_payout_environment
        FOREIGN KEY (payout_id, environment) REFERENCES owner_payouts(id, environment) ON DELETE RESTRICT,
    CONSTRAINT ux_owner_payout_items_payout_booking UNIQUE (payout_id, booking_id),
    CONSTRAINT ck_owner_payout_items_amount CHECK (amount > 0 AND amount <> 'NaN'::numeric AND amount = trunc(amount)),
    CONSTRAINT ck_owner_payout_items_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_owner_payout_items_environment CHECK (environment IN ('SIMULATOR', 'TEST', 'LIVE')),
    CONSTRAINT ck_owner_payout_items_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'RELEASED'))
);
CREATE UNIQUE INDEX ux_owner_payout_items_active_booking
    ON owner_payout_items(booking_id) WHERE status IN ('PENDING', 'SUCCEEDED');
CREATE INDEX idx_owner_payout_items_payout ON owner_payout_items(payout_id, id);

CREATE TABLE payout_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id uuid NOT NULL,
    sequence_no integer NOT NULL,
    execution_mode varchar(30) NOT NULL,
    request_key uuid NOT NULL,
    payload_hash varchar(64) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    amount numeric(19,2) NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'VND',
    environment varchar(10) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'STARTED',
    transfer_reference varchar(255),
    failure_code varchar(100),
    note varchar(2000),
    started_at timestamptz NOT NULL,
    performed_at timestamptz,
    completed_at timestamptz,
    performed_by uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT fk_payout_attempts_payout_environment
        FOREIGN KEY (payout_id, environment) REFERENCES owner_payouts(id, environment) ON DELETE RESTRICT,
    CONSTRAINT ux_payout_attempts_id_payout UNIQUE (id, payout_id),
    CONSTRAINT ux_payout_attempts_sequence UNIQUE (payout_id, sequence_no),
    CONSTRAINT ux_payout_attempts_request UNIQUE (payout_id, request_key),
    CONSTRAINT ck_payout_attempts_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_payout_attempts_mode CHECK (execution_mode IN ('SIMULATOR', 'MANUAL_RECORD')),
    CONSTRAINT ck_payout_attempts_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payout_attempts_key CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_payout_attempts_amount CHECK (amount > 0 AND amount <> 'NaN'::numeric AND amount = trunc(amount)),
    CONSTRAINT ck_payout_attempts_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_payout_attempts_environment CHECK (environment IN ('SIMULATOR', 'TEST', 'LIVE')),
    CONSTRAINT ck_payout_attempts_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_payout_attempts_outcome CHECK (
        (status = 'STARTED' AND completed_at IS NULL AND performed_at IS NULL
            AND transfer_reference IS NULL AND failure_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND performed_at IS NOT NULL
            AND completed_at >= started_at AND performed_at <= completed_at
            AND NULLIF(btrim(transfer_reference), '') IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND performed_at IS NOT NULL
            AND completed_at >= started_at AND performed_at <= completed_at
            AND transfer_reference IS NULL AND NULLIF(btrim(failure_code), '') IS NOT NULL)
    )
);
CREATE UNIQUE INDEX ux_payout_attempts_one_succeeded
    ON payout_attempts(payout_id) WHERE status = 'SUCCEEDED';
CREATE INDEX idx_payout_attempts_payout_sequence ON payout_attempts(payout_id, sequence_no);
ALTER TABLE owner_payouts ADD CONSTRAINT fk_owner_payouts_successful_attempt
    FOREIGN KEY (successful_attempt_id, id) REFERENCES payout_attempts(id, payout_id) ON DELETE RESTRICT;

CREATE TABLE owner_adjustments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    booking_id uuid REFERENCES bookings(id) ON DELETE RESTRICT,
    refund_id uuid REFERENCES refunds(id) ON DELETE RESTRICT,
    source_payout_item_id uuid REFERENCES owner_payout_items(id) ON DELETE RESTRICT,
    amount numeric(19,2) NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'VND',
    environment varchar(10) NOT NULL,
    reason varchar(40) NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ck_owner_adjustments_amount CHECK (amount <> 0 AND amount <> 'NaN'::numeric AND amount = trunc(amount)),
    CONSTRAINT ck_owner_adjustments_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_owner_adjustments_environment CHECK (environment IN ('SIMULATOR', 'TEST', 'LIVE')),
    CONSTRAINT ck_owner_adjustments_reason CHECK (reason IN ('REFUND_AFTER_PAYOUT', 'DISPUTE_RECOVERY', 'MANUAL_CORRECTION')),
    CONSTRAINT ck_owner_adjustments_recovery CHECK (
        reason <> 'REFUND_AFTER_PAYOUT' OR
        (booking_id IS NOT NULL AND refund_id IS NOT NULL AND source_payout_item_id IS NOT NULL AND amount < 0)
    )
);
CREATE UNIQUE INDEX ux_owner_adjustments_refund_payout_item
    ON owner_adjustments(refund_id, source_payout_item_id)
    WHERE refund_id IS NOT NULL AND source_payout_item_id IS NOT NULL;
CREATE INDEX idx_owner_adjustments_owner_environment ON owner_adjustments(owner_id, environment, recorded_at);
CREATE INDEX idx_owner_adjustments_booking ON owner_adjustments(booking_id) WHERE booking_id IS NOT NULL;

CREATE OR REPLACE FUNCTION guard_owner_payout_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payout financial history cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF OLD.status IN ('SUCCEEDED', 'CANCELLED') OR
       (OLD.payout_code, OLD.owner_id, OLD.amount, OLD.currency, OLD.environment, OLD.period_start, OLD.period_end)
       IS DISTINCT FROM
       (NEW.payout_code, NEW.owner_id, NEW.amount, NEW.currency, NEW.environment, NEW.period_start, NEW.period_end) OR
       (OLD.status = 'PENDING' AND NEW.status NOT IN ('PENDING', 'SUCCEEDED', 'CANCELLED')) THEN
        RAISE EXCEPTION 'invalid payout history mutation' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_owner_payout_history BEFORE UPDATE OR DELETE ON owner_payouts
    FOR EACH ROW EXECUTE FUNCTION guard_owner_payout_history();

CREATE OR REPLACE FUNCTION guard_owner_payout_item_history() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status varchar(20);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payout items cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF OLD.status <> 'PENDING' OR
       (OLD.payout_id, OLD.booking_id, OLD.payment_id, OLD.amount, OLD.currency, OLD.environment)
       IS DISTINCT FROM
       (NEW.payout_id, NEW.booking_id, NEW.payment_id, NEW.amount, NEW.currency, NEW.environment) THEN
        RAISE EXCEPTION 'invalid payout item history mutation' USING ERRCODE = '55000';
    END IF;
    SELECT status INTO parent_status FROM owner_payouts WHERE id = NEW.payout_id;
    IF (NEW.status = 'SUCCEEDED' AND parent_status <> 'SUCCEEDED') OR
       (NEW.status = 'RELEASED' AND parent_status <> 'CANCELLED') THEN
        RAISE EXCEPTION 'payout item status disagrees with payout' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_owner_payout_item_history BEFORE UPDATE OR DELETE ON owner_payout_items
    FOR EACH ROW EXECUTE FUNCTION guard_owner_payout_item_history();

CREATE OR REPLACE FUNCTION guard_payout_attempt_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payout attempts cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF OLD.status <> 'STARTED' OR
       (OLD.payout_id, OLD.sequence_no, OLD.execution_mode, OLD.request_key, OLD.payload_hash,
        OLD.idempotency_key, OLD.amount, OLD.currency, OLD.environment, OLD.started_at, OLD.performed_by)
       IS DISTINCT FROM
       (NEW.payout_id, NEW.sequence_no, NEW.execution_mode, NEW.request_key, NEW.payload_hash,
        NEW.idempotency_key, NEW.amount, NEW.currency, NEW.environment, NEW.started_at, NEW.performed_by) THEN
        RAISE EXCEPTION 'invalid payout attempt history mutation' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_payout_attempt_history BEFORE UPDATE OR DELETE ON payout_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_payout_attempt_history();

CREATE OR REPLACE FUNCTION guard_owner_adjustment_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'owner adjustments are append-only' USING ERRCODE = '55000';
END; $$;
CREATE TRIGGER trg_owner_adjustment_history BEFORE UPDATE OR DELETE ON owner_adjustments
    FOR EACH ROW EXECUTE FUNCTION guard_owner_adjustment_history();

CREATE OR REPLACE FUNCTION validate_owner_payout_item_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_booking uuid; source_currency varchar(3); source_environment varchar(10); source_owner uuid; payout_owner uuid;
BEGIN
    SELECT p.booking_id, p.currency, p.environment, b.financial_owner_id
      INTO source_booking, source_currency, source_environment, source_owner
      FROM payments p
      JOIN bookings b ON b.id = p.booking_id
     WHERE p.id = NEW.payment_id;
    SELECT owner_id INTO payout_owner FROM owner_payouts WHERE id = NEW.payout_id;
    IF source_booking IS DISTINCT FROM NEW.booking_id OR source_currency IS DISTINCT FROM NEW.currency OR
       source_environment IS DISTINCT FROM NEW.environment OR source_owner IS DISTINCT FROM payout_owner THEN
        RAISE EXCEPTION 'payout item source, owner or environment mismatch' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_owner_payout_item_source BEFORE INSERT ON owner_payout_items
    FOR EACH ROW EXECUTE FUNCTION validate_owner_payout_item_source();

CREATE OR REPLACE FUNCTION validate_owner_adjustment_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_booking uuid; source_owner uuid; source_environment varchar(10); source_amount numeric(19,2); refund_booking uuid;
BEGIN
    IF NEW.reason = 'REFUND_AFTER_PAYOUT' THEN
        SELECT i.booking_id, p.owner_id, i.environment, i.amount
          INTO source_booking, source_owner, source_environment, source_amount
          FROM owner_payout_items i JOIN owner_payouts p ON p.id = i.payout_id
         WHERE i.id = NEW.source_payout_item_id AND i.status = 'SUCCEEDED' AND p.status = 'SUCCEEDED';
        SELECT booking_id INTO refund_booking FROM refunds WHERE id = NEW.refund_id;
        IF source_booking IS DISTINCT FROM NEW.booking_id OR refund_booking IS DISTINCT FROM NEW.booking_id OR
           source_owner IS DISTINCT FROM NEW.owner_id OR source_environment IS DISTINCT FROM NEW.environment OR
           NEW.amount IS DISTINCT FROM -source_amount THEN
            RAISE EXCEPTION 'adjustment source mismatch or payout was not successful' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_owner_adjustment_source BEFORE INSERT ON owner_adjustments
    FOR EACH ROW EXECUTE FUNCTION validate_owner_adjustment_source();

-- Deferred checks allow one transaction to create Payout -> Items, or complete
-- Attempt -> Payout -> Items, without an invalid committed intermediate state.
CREATE OR REPLACE FUNCTION validate_owner_payout_aggregate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE payout_id_to_check uuid; payout_row owner_payouts%ROWTYPE;
        item_count bigint; item_amount numeric; pending_items bigint; succeeded_items bigint; released_items bigint;
        successful_attempts bigint; started_attempts bigint; matching_successful_attempts bigint; mismatched_attempts bigint;
BEGIN
    IF TG_TABLE_NAME = 'owner_payouts' THEN payout_id_to_check := NEW.id;
    ELSE payout_id_to_check := NEW.payout_id; END IF;
    SELECT * INTO payout_row FROM owner_payouts WHERE id = payout_id_to_check;
    SELECT count(*), coalesce(sum(amount), 0),
           count(*) FILTER (WHERE status = 'PENDING'),
           count(*) FILTER (WHERE status = 'SUCCEEDED'),
           count(*) FILTER (WHERE status = 'RELEASED')
      INTO item_count, item_amount, pending_items, succeeded_items, released_items
      FROM owner_payout_items WHERE payout_id = payout_id_to_check;
    SELECT count(*) FILTER (WHERE status = 'SUCCEEDED'),
           count(*) FILTER (WHERE status = 'STARTED'),
           count(*) FILTER (WHERE status = 'SUCCEEDED' AND id = payout_row.successful_attempt_id),
           count(*) FILTER (WHERE amount IS DISTINCT FROM payout_row.amount OR currency IS DISTINCT FROM payout_row.currency)
      INTO successful_attempts, started_attempts, matching_successful_attempts, mismatched_attempts
      FROM payout_attempts WHERE payout_id = payout_id_to_check;
    IF item_count = 0 OR item_amount IS DISTINCT FROM payout_row.amount OR mismatched_attempts <> 0 OR
       (payout_row.status = 'PENDING' AND (pending_items <> item_count OR successful_attempts <> 0)) OR
       (payout_row.status = 'SUCCEEDED' AND (succeeded_items <> item_count OR successful_attempts <> 1 OR
           matching_successful_attempts <> 1 OR started_attempts <> 0)) OR
       (payout_row.status = 'CANCELLED' AND (released_items <> item_count OR successful_attempts <> 0 OR started_attempts <> 0)) THEN
        RAISE EXCEPTION 'payout aggregate is inconsistent at commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER trg_owner_payout_aggregate
    AFTER INSERT OR UPDATE ON owner_payouts DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_owner_payout_aggregate();
CREATE CONSTRAINT TRIGGER trg_owner_payout_item_aggregate
    AFTER INSERT OR UPDATE ON owner_payout_items DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_owner_payout_aggregate();
CREATE CONSTRAINT TRIGGER trg_payout_attempt_aggregate
    AFTER INSERT OR UPDATE ON payout_attempts DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_owner_payout_aggregate();

CREATE VIEW v_owner_booking_financials AS
WITH receipt_totals AS (
    SELECT payment_id,
           sum(amount) AS gross_collected_amount,
           sum(amount) FILTER (WHERE application_classification = 'APPLIED') AS applied_amount
      FROM payment_transactions WHERE payment_id IS NOT NULL GROUP BY payment_id
), refund_totals AS (
    SELECT booking_id,
           sum(amount) FILTER (WHERE status = 'PENDING') AS refund_pending_amount,
           sum(amount) FILTER (WHERE status = 'SUCCEEDED') AS refund_succeeded_amount,
           count(*) AS refund_count
      FROM refunds GROUP BY booking_id
), payout_totals AS (
    SELECT i.booking_id,
           sum(i.amount) FILTER (WHERE i.status = 'PENDING' AND p.status = 'PENDING') AS payout_pending_amount,
           sum(i.amount) FILTER (WHERE i.status = 'SUCCEEDED' AND p.status = 'SUCCEEDED') AS paid_to_owner_amount,
           count(*) FILTER (WHERE i.status IN ('PENDING', 'SUCCEEDED')) AS reserved_count
      FROM owner_payout_items i JOIN owner_payouts p ON p.id = i.payout_id GROUP BY i.booking_id
), adjustment_totals AS (
    SELECT booking_id, sum(-amount) FILTER (WHERE amount < 0) AS adjustment_due_amount
      FROM owner_adjustments WHERE booking_id IS NOT NULL GROUP BY booking_id
), booking_financials AS (
    SELECT b.id AS booking_id, b.booking_code, b.status AS booking_status, b.cancellation_reason,
           s.id AS station_id, b.financial_owner_id AS owner_id, p.id AS payment_id, p.status AS payment_status,
           p.environment, p.currency, p.amount AS expected_package_amount, p.needs_reconciliation,
           coalesce(rt.gross_collected_amount, 0::numeric) AS gross_collected_amount,
           coalesce(rt.applied_amount, 0::numeric) AS applied_amount,
           coalesce(rf.refund_pending_amount, 0::numeric) AS refund_pending_amount,
           coalesce(rf.refund_succeeded_amount, 0::numeric) AS refund_succeeded_amount,
           coalesce(rf.refund_count, 0) AS refund_count,
           coalesce(po.payout_pending_amount, 0::numeric) AS payout_pending_amount,
           coalesce(po.paid_to_owner_amount, 0::numeric) AS paid_to_owner_amount,
           coalesce(po.reserved_count, 0) AS reserved_count,
           coalesce(ad.adjustment_due_amount, 0::numeric) AS adjustment_due_amount,
           EXISTS (
               SELECT 1 FROM support_tickets t
                WHERE t.booking_id = b.id AND t.category = 'CHARGING_ISSUE'
                  AND coalesce((
                      SELECT f.conclusion FROM ticket_findings f
                       WHERE f.ticket_id = t.id AND f.booking_id = b.id
                       ORDER BY f.recorded_at DESC, f.id DESC LIMIT 1
                  ), 'UNRESOLVED') <> 'NOT_STATION_FAILURE'
           ) AS incident_held
      FROM bookings b
      JOIN payments p ON p.booking_id = b.id AND p.environment IN ('SIMULATOR', 'TEST', 'LIVE')
      JOIN connectors c ON c.id = b.connector_id
      JOIN charge_points cp ON cp.id = c.charge_point_id
      JOIN stations s ON s.id = cp.station_id
      LEFT JOIN receipt_totals rt ON rt.payment_id = p.id
      LEFT JOIN refund_totals rf ON rf.booking_id = b.id
      LEFT JOIN payout_totals po ON po.booking_id = b.id
      LEFT JOIN adjustment_totals ad ON ad.booking_id = b.id
)
SELECT f.booking_id, f.booking_code, f.owner_id, f.station_id, f.payment_id,
       f.environment, f.currency, f.expected_package_amount, f.gross_collected_amount,
       f.applied_amount, f.refund_pending_amount, f.refund_succeeded_amount,
       f.payout_pending_amount, f.paid_to_owner_amount, f.adjustment_due_amount,
       f.incident_held,
       (f.booking_status = 'COMPLETED' OR
        (f.booking_status = 'CANCELLED' AND f.cancellation_reason IN ('NO_SHOW', 'DRIVER_CANCELLED')))
       AND f.payment_status = 'PAID' AND f.currency = 'VND'
       AND NOT f.needs_reconciliation AND f.applied_amount = f.expected_package_amount
       AND f.refund_count = 0 AND f.reserved_count = 0 AND NOT f.incident_held
       AS is_eligible_for_payout,
       CASE WHEN
           (f.booking_status = 'COMPLETED' OR
            (f.booking_status = 'CANCELLED' AND f.cancellation_reason IN ('NO_SHOW', 'DRIVER_CANCELLED')))
           AND f.payment_status = 'PAID' AND f.currency = 'VND'
           AND NOT f.needs_reconciliation AND f.applied_amount = f.expected_package_amount
           AND f.refund_count = 0 AND f.reserved_count = 0 AND NOT f.incident_held
           THEN f.applied_amount ELSE 0::numeric END AS eligible_amount
  FROM booking_financials f;
