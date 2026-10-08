-- BKG-066/072: durable email intent in the same transaction as booking confirmation.
CREATE TABLE notification_outbox (
    event_id uuid PRIMARY KEY,
    source_receipt_id uuid NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    source_event_key varchar(160) NOT NULL,
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE RESTRICT,
    station_id uuid NOT NULL REFERENCES stations(id) ON DELETE RESTRICT,
    recipient_id uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    audience varchar(20) NOT NULL,
    channel varchar(20) NOT NULL DEFAULT 'EMAIL',
    event_type varchar(40) NOT NULL,
    schema_version integer NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_until timestamptz,
    claim_token uuid,
    published_at timestamptz,
    sanitized_last_error varchar(500),
    CONSTRAINT ux_notification_outbox_intent UNIQUE(source_event_key, recipient_id, audience, channel),
    CONSTRAINT ck_notification_outbox_source CHECK(btrim(source_event_key) <> ''),
    CONSTRAINT ck_notification_outbox_slice CHECK(
        event_type = 'BOOKING_CONFIRMED' AND audience = 'DRIVER' AND channel = 'EMAIL' AND schema_version = 1),
    CONSTRAINT ck_notification_outbox_status CHECK(status IN ('PENDING','PROCESSING','PUBLISHED')),
    CONSTRAINT ck_notification_outbox_attempts CHECK(attempt_count >= 0),
    CONSTRAINT ck_notification_outbox_claim CHECK(
        (status = 'PROCESSING' AND lease_until IS NOT NULL AND claim_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_until IS NULL AND claim_token IS NULL)),
    CONSTRAINT ck_notification_outbox_published CHECK(
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)),
    CONSTRAINT ck_notification_outbox_payload CHECK((
        jsonb_typeof(payload) = 'object'
        AND payload ?& ARRAY['eventId','schemaVersion','eventType','sourceEventKey',
                            'bookingId','stationId','recipientId','audience','occurredAt']
        AND payload->>'eventId' = event_id::text
        AND payload->>'schemaVersion' = schema_version::text
        AND payload->>'eventType' = event_type
        AND payload->>'sourceEventKey' = source_event_key
        AND payload->>'bookingId' = booking_id::text
        AND payload->>'stationId' = station_id::text
        AND payload->>'recipientId' = recipient_id::text
        AND payload->>'audience' = audience
        AND jsonb_typeof(payload->'occurredAt') = 'string'
        AND jsonb_typeof(payload->'schemaVersion') = 'number'
        AND payload - ARRAY['eventId','schemaVersion','eventType','sourceEventKey',
                            'bookingId','stationId','recipientId','audience','occurredAt'] = '{}'::jsonb) IS TRUE)
);

CREATE INDEX idx_notification_outbox_pending
    ON notification_outbox(next_attempt_at, occurred_at, event_id) WHERE status = 'PENDING';
CREATE INDEX idx_notification_outbox_leases
    ON notification_outbox(lease_until, event_id) WHERE status = 'PROCESSING';
