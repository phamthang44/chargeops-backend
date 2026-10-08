-- Email ingestion only; inbox and domain commands remain independent of Kafka consumers.
ALTER TABLE notification_outbox ADD CONSTRAINT ux_notification_outbox_event_recipient UNIQUE(event_id, recipient_id);

CREATE TABLE notification_email_deliveries (
    delivery_id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    channel varchar(20) NOT NULL DEFAULT 'EMAIL',
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until timestamptz,
    claim_token uuid,
    send_started_at timestamptz,
    sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    skip_reason varchar(40),
    sanitized_last_error varchar(500),
    CONSTRAINT fk_email_delivery_event_recipient FOREIGN KEY(event_id, recipient_id)
        REFERENCES notification_outbox(event_id, recipient_id) ON DELETE RESTRICT,
    CONSTRAINT ux_email_delivery_intent UNIQUE(event_id, recipient_id, channel),
    CONSTRAINT ck_email_delivery_channel CHECK(channel = 'EMAIL'),
    CONSTRAINT ck_email_delivery_status CHECK(status IN ('PENDING','PROCESSING','SENT','FAILED','SKIPPED','UNKNOWN')),
    CONSTRAINT ck_email_delivery_attempts CHECK(attempt_count >= 0),
    CONSTRAINT ck_email_delivery_claim CHECK(
        (status = 'PROCESSING' AND lease_until IS NOT NULL AND claim_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_until IS NULL AND claim_token IS NULL)),
    CONSTRAINT ck_email_delivery_sent CHECK(
        (status = 'SENT' AND sent_at IS NOT NULL AND send_started_at IS NOT NULL)
        OR (status <> 'SENT' AND sent_at IS NULL)),
    CONSTRAINT ck_email_delivery_unknown CHECK(status <> 'UNKNOWN' OR send_started_at IS NOT NULL),
    CONSTRAINT ck_email_delivery_not_started CHECK(status NOT IN ('PENDING','SKIPPED') OR send_started_at IS NULL),
    CONSTRAINT ck_email_delivery_skip CHECK(
        (status = 'SKIPPED' AND skip_reason IS NOT NULL
            AND skip_reason IN ('RECIPIENT_INACTIVE','RECIPIENT_SCOPE_CHANGED','RESOURCE_UNAVAILABLE'))
        OR (status <> 'SKIPPED' AND skip_reason IS NULL))
);

CREATE INDEX idx_email_delivery_pending ON notification_email_deliveries(next_attempt_at, created_at, delivery_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_email_delivery_processing ON notification_email_deliveries(lease_until, delivery_id)
    WHERE status = 'PROCESSING';
