-- BKG-030: full-package refund obligations and minimal execution attempts.
-- No refund allocations, wallet balance, automatic retry or provider reconciliation.

CREATE TABLE refunds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    source_payment_transaction_id uuid NOT NULL,
    amount numeric(19,2) NOT NULL,
    currency varchar(3) NOT NULL,
    reason varchar(40) NOT NULL,
    basis_type varchar(40) NOT NULL,
    basis_id uuid NOT NULL,
    status varchar(20) NOT NULL,
    decision_at timestamptz NOT NULL,
    decided_by uuid NOT NULL,
    successful_attempt_id uuid,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,

    CONSTRAINT fk_refunds_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_payment
        FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_source_transaction
        FOREIGN KEY (source_payment_transaction_id) REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_decided_by
        FOREIGN KEY (decided_by) REFERENCES user_profile(id) ON DELETE RESTRICT,
    CONSTRAINT ux_refunds_source_transaction UNIQUE (source_payment_transaction_id),
    CONSTRAINT ux_refunds_basis UNIQUE (basis_type, basis_id),
    CONSTRAINT ck_refunds_amount
        CHECK (amount > 0 AND amount <> 'NaN'::numeric AND amount = trunc(amount)),
    CONSTRAINT ck_refunds_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_refunds_reason CHECK (reason IN ('VOLUNTARY_GRACE', 'STATION_FAILURE')),
    CONSTRAINT ck_refunds_basis_type
        CHECK (basis_type IN ('BOOKING_CANCELLATION', 'STATION_FAILURE_FINDING')),
    CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED')),
    CONSTRAINT ck_refunds_completion CHECK (
        (status = 'PENDING' AND successful_attempt_id IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND successful_attempt_id IS NOT NULL AND completed_at IS NOT NULL
            AND completed_at >= decision_at)
    )
);

CREATE INDEX idx_refunds_status_decision ON refunds(status, decision_at, id);
CREATE INDEX idx_refunds_booking ON refunds(booking_id);
CREATE INDEX idx_refunds_payment ON refunds(payment_id);

CREATE TABLE refund_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id uuid NOT NULL,
    sequence_no integer NOT NULL,
    execution_mode varchar(30) NOT NULL,
    request_key uuid NOT NULL,
    payload_hash varchar(64) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    status varchar(20) NOT NULL,
    provider_refund_id varchar(255),
    transfer_reference varchar(255),
    failure_code varchar(100),
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    performed_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,

    CONSTRAINT fk_refund_attempts_refund
        FOREIGN KEY (refund_id) REFERENCES refunds(id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_attempts_performed_by
        FOREIGN KEY (performed_by) REFERENCES user_profile(id) ON DELETE RESTRICT,
    CONSTRAINT ux_refund_attempts_request UNIQUE (refund_id, request_key),
    CONSTRAINT ux_refund_attempts_sequence UNIQUE (refund_id, sequence_no),
    CONSTRAINT ux_refund_attempts_id_refund UNIQUE (id, refund_id),
    CONSTRAINT ck_refund_attempts_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_refund_attempts_execution_mode
        CHECK (execution_mode IN ('SIMULATOR', 'MANUAL_RECORD')),
    CONSTRAINT ck_refund_attempts_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refund_attempts_idempotency_key CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_refund_attempts_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_refund_attempts_terminal_state CHECK (
        (status = 'STARTED' AND completed_at IS NULL
            AND transfer_reference IS NULL AND failure_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL
            AND completed_at >= started_at
            AND NULLIF(btrim(transfer_reference), '') IS NOT NULL
            AND failure_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL
            AND completed_at >= started_at
            AND transfer_reference IS NULL
            AND NULLIF(btrim(failure_code), '') IS NOT NULL)
    ),
    CONSTRAINT ck_refund_attempts_provider_ref
        CHECK (provider_refund_id IS NULL OR btrim(provider_refund_id) <> '')
);

CREATE INDEX idx_refund_attempts_refund_started
    ON refund_attempts(refund_id, started_at, id);

CREATE UNIQUE INDEX ux_refund_attempts_one_succeeded
    ON refund_attempts(refund_id)
    WHERE status = 'SUCCEEDED';

-- Proves that the selected successful attempt belongs to the same Refund.
-- No DEFERRABLE is needed: persist Refund(NULL), then Attempt, then update Refund.
ALTER TABLE refunds
    ADD CONSTRAINT fk_refunds_successful_attempt
    FOREIGN KEY (successful_attempt_id, id)
    REFERENCES refund_attempts(id, refund_id)
    ON DELETE RESTRICT;

-- Cross-table invariant for the full-package M2 scope. A regular BEFORE trigger
-- is intentional: it rejects invalid source evidence at the mutation boundary.
CREATE OR REPLACE FUNCTION validate_full_package_refund_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    source_payment_id uuid;
    source_classification varchar(30);
    source_amount numeric(19,2);
    source_currency varchar(3);
    payment_booking_id uuid;
    payment_amount numeric(19,2);
    payment_currency varchar(3);
    payment_status varchar(30);
BEGIN
    SELECT payment_id, application_classification, amount, currency
      INTO source_payment_id, source_classification, source_amount, source_currency
      FROM payment_transactions
     WHERE id = NEW.source_payment_transaction_id;

    SELECT booking_id, amount, currency, status
      INTO payment_booking_id, payment_amount, payment_currency, payment_status
      FROM payments
     WHERE id = NEW.payment_id;

    IF source_payment_id IS NULL
       OR source_classification <> 'APPLIED'
       OR source_payment_id <> NEW.payment_id
       OR payment_booking_id <> NEW.booking_id
       OR payment_status <> 'PAID'
       OR source_currency <> 'VND'
       OR payment_currency <> 'VND'
       OR source_amount IS DISTINCT FROM NEW.amount
       OR payment_amount IS DISTINCT FROM NEW.amount THEN
        RAISE EXCEPTION 'refund source must be the APPLIED exact full-package receipt'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_refunds_validate_full_package_source
    BEFORE INSERT OR UPDATE OF booking_id, payment_id, source_payment_transaction_id, amount, currency
    ON refunds
    FOR EACH ROW
    EXECUTE FUNCTION validate_full_package_refund_source();

COMMENT ON TABLE refunds IS
    'Full-package refund obligations. FAILED attempts do not erase a PENDING obligation.';
COMMENT ON TABLE refund_attempts IS
    'Auditable Simulator or manual-record execution attempts; no automatic retry semantics.';
