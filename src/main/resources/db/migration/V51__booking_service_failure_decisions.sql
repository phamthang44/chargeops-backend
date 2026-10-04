-- BKG-056: one full-package obligation per payment and immutable responsibility receipts.
DO $$
BEGIN
    IF EXISTS (SELECT payment_id FROM refunds GROUP BY payment_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Duplicate refund payments require manual reconciliation before V51';
    END IF;
END $$;
ALTER TABLE refunds ADD CONSTRAINT ux_refunds_payment UNIQUE (payment_id);
ALTER TABLE refunds DROP CONSTRAINT ck_refunds_basis_type;
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_basis_type
    CHECK (basis_type IN ('BOOKING_CANCELLATION', 'STATION_FAILURE_FINDING', 'SERVICE_FAILURE_DECISION'));
CREATE TABLE booking_service_failure_decisions (
    id uuid PRIMARY KEY,
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE RESTRICT,
    payment_id uuid NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    command_id uuid NOT NULL REFERENCES booking_commands(id) ON DELETE RESTRICT,
    sequence_no bigint NOT NULL CHECK (sequence_no > 0),
    kind varchar(50) NOT NULL,
    result varchar(40) NOT NULL,
    actor_profile_id uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    actor_kind varchar(20) NOT NULL,
    actor_display_name_snapshot varchar(255) NOT NULL CHECK (length(btrim(actor_display_name_snapshot)) > 0),
    station_id uuid NOT NULL REFERENCES stations(id) ON DELETE RESTRICT,
    owner_profile_id_snapshot uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    reason varchar(2000) NOT NULL CHECK (length(btrim(reason)) BETWEEN 5 AND 2000),
    affected_at timestamptz,
    decided_at timestamptz NOT NULL,
    policy_code varchar(100) NOT NULL,
    policy_version varchar(100) NOT NULL,
    ticket_id uuid REFERENCES support_tickets(id) ON DELETE RESTRICT,
    escalation_id uuid REFERENCES ticket_escalations(id) ON DELETE RESTRICT,
    source_receipt_id uuid REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    refund_id uuid REFERENCES refunds(id) ON DELETE RESTRICT,
    amount_vnd numeric(19,2) NOT NULL CHECK (amount_vnd >= 0 AND amount_vnd <> 'NaN'::numeric AND amount_vnd = trunc(amount_vnd)),
    currency varchar(3) NOT NULL CHECK (currency = 'VND'),
    evidence_snapshot jsonb NOT NULL CHECK (jsonb_typeof(evidence_snapshot) = 'object'),
    CONSTRAINT ux_service_failure_command UNIQUE (command_id),
    CONSTRAINT ux_service_failure_sequence UNIQUE (booking_id, sequence_no),
    CONSTRAINT ck_service_failure_authority CHECK (
        (kind IN ('OWNER_CANCEL_BOOKING', 'OWNER_ACCEPT_STATION_FAILURE') AND actor_kind = 'OWNER'
         AND ticket_id IS NULL AND escalation_id IS NULL)
        OR (kind = 'ADMIN_REVIEW_STATION_FAILURE' AND actor_kind = 'ADMIN'
         AND ticket_id IS NOT NULL AND escalation_id IS NOT NULL)),
    CONSTRAINT ck_service_failure_result CHECK (
        (result = 'FULL_REFUND' AND source_receipt_id IS NOT NULL AND refund_id IS NOT NULL AND amount_vnd > 0 AND affected_at IS NOT NULL)
        OR (result = 'NO_APPLIED_PAYMENT' AND kind = 'OWNER_CANCEL_BOOKING'
            AND source_receipt_id IS NULL AND refund_id IS NULL AND amount_vnd = 0 AND affected_at IS NOT NULL)
        OR (result = 'INSUFFICIENT_EVIDENCE' AND kind = 'ADMIN_REVIEW_STATION_FAILURE'
            AND source_receipt_id IS NULL AND refund_id IS NULL AND amount_vnd = 0 AND affected_at IS NULL)),
    CONSTRAINT ck_service_failure_time CHECK (affected_at IS NULL OR affected_at <= decided_at)
);
CREATE INDEX idx_service_failure_booking ON booking_service_failure_decisions(booking_id, sequence_no DESC);
CREATE INDEX idx_service_failure_escalation ON booking_service_failure_decisions(escalation_id, sequence_no DESC);
CREATE UNIQUE INDEX ux_service_failure_owner ON booking_service_failure_decisions(booking_id) WHERE actor_kind = 'OWNER';
CREATE FUNCTION reject_service_failure_decision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'booking_service_failure_decisions is append-only' USING ERRCODE = '55000';
END $$;
CREATE TRIGGER trg_service_failure_append_only BEFORE UPDATE OR DELETE ON booking_service_failure_decisions
    FOR EACH ROW EXECUTE FUNCTION reject_service_failure_decision_mutation();
