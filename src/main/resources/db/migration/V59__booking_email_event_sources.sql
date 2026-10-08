-- Expand event coverage without changing old receipts/commands or replaying historical mail.
ALTER TABLE notification_outbox
    ADD COLUMN source_history_id uuid REFERENCES booking_status_history(id) ON DELETE RESTRICT,
    ADD COLUMN source_decision_id uuid REFERENCES booking_service_failure_decisions(id) ON DELETE RESTRICT,
    ADD COLUMN source_refund_id uuid REFERENCES refunds(id) ON DELETE RESTRICT,
    ADD COLUMN source_attempt_id uuid REFERENCES refund_attempts(id) ON DELETE RESTRICT,
    ADD COLUMN reminder_start_at timestamptz;
ALTER TABLE notification_outbox DROP CONSTRAINT ck_notification_outbox_slice;
ALTER TABLE notification_outbox DROP CONSTRAINT ck_notification_outbox_source_kind;
ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_slice CHECK (
    channel = 'EMAIL' AND schema_version = 1 AND (
        (audience = 'DRIVER' AND event_type IN ('BOOKING_CONFIRMED','BOOKING_CANCELLED','BOOKING_NO_SHOW',
            'BOOKING_COMPLETED','BOOKING_REMINDER','RESPONSIBILITY_DECIDED','REFUND_PENDING','REFUND_SUCCEEDED'))
        OR (audience = 'OWNER' AND event_type IN ('REFUND_ATTEMPT_FAILED','REFUND_SUCCEEDED'))));
ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_source_kind CHECK (
    num_nonnulls(source_receipt_id,source_command_id,source_history_id,source_decision_id,
        source_refund_id,source_attempt_id,reminder_start_at) = 1 AND (
    (event_type = 'BOOKING_CONFIRMED' AND source_receipt_id IS NOT NULL)
    OR (event_type = 'BOOKING_CANCELLED' AND source_command_id IS NOT NULL
        AND source_event_key = source_command_id::text || ':BOOKING_CANCELLED')
    OR (event_type IN ('BOOKING_NO_SHOW','BOOKING_COMPLETED') AND source_history_id IS NOT NULL
        AND source_event_key = source_history_id::text || ':' || event_type)
    OR (event_type = 'RESPONSIBILITY_DECIDED' AND source_decision_id IS NOT NULL
        AND source_event_key = source_decision_id::text || ':RESPONSIBILITY_DECIDED')
    OR (event_type = 'REFUND_PENDING' AND source_refund_id IS NOT NULL
        AND source_event_key = source_refund_id::text || ':REFUND_PENDING')
    OR (event_type IN ('REFUND_ATTEMPT_FAILED','REFUND_SUCCEEDED') AND source_attempt_id IS NOT NULL
        AND source_event_key = source_attempt_id::text || ':' || event_type)
    OR (event_type = 'BOOKING_REMINDER' AND reminder_start_at IS NOT NULL)));
CREATE INDEX idx_booking_reminder_candidates ON bookings(start_at,id) WHERE status='CONFIRMED' AND checked_in_at IS NULL;
CREATE UNIQUE INDEX ux_notification_outbox_reminder ON notification_outbox(booking_id,reminder_start_at)
    WHERE event_type='BOOKING_REMINDER';
ALTER TABLE notification_email_deliveries DROP CONSTRAINT ck_email_delivery_skip;
ALTER TABLE notification_email_deliveries ADD CONSTRAINT ck_email_delivery_skip CHECK (
    (status='SKIPPED' AND skip_reason IS NOT NULL AND skip_reason IN
        ('RECIPIENT_INACTIVE','RECIPIENT_SCOPE_CHANGED','RESOURCE_UNAVAILABLE','EVENT_SUPERSEDED','REMINDER_NO_LONGER_ELIGIBLE'))
    OR (status<>'SKIPPED' AND skip_reason IS NULL));
