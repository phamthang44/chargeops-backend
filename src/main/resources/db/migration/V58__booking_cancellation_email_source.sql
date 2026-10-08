-- Preserve V56/V57 confirmation intents and deliveries; cancellation has its own command source.
ALTER TABLE notification_outbox ALTER COLUMN source_receipt_id DROP NOT NULL;
ALTER TABLE notification_outbox ADD COLUMN source_command_id uuid
    REFERENCES booking_commands(id) ON DELETE RESTRICT;
ALTER TABLE notification_outbox DROP CONSTRAINT ck_notification_outbox_slice;
ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_slice CHECK (
    event_type IN ('BOOKING_CONFIRMED', 'BOOKING_CANCELLED')
    AND audience = 'DRIVER' AND channel = 'EMAIL' AND schema_version = 1);
ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_source_kind CHECK (
    (event_type = 'BOOKING_CONFIRMED' AND source_receipt_id IS NOT NULL AND source_command_id IS NULL)
    OR (event_type = 'BOOKING_CANCELLED' AND source_receipt_id IS NULL AND source_command_id IS NOT NULL
        AND source_event_key = source_command_id::text || ':BOOKING_CANCELLED'));
