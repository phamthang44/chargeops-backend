-- Reporter-facing assignment notice: audience PERSONAL alongside the handler audiences
-- (OWNER/STAFF/ADMIN) that TICKET_ASSIGNED already supports.
ALTER TABLE app_notifications DROP CONSTRAINT ck_notifications_typed_event;
ALTER TABLE app_notifications ADD CONSTRAINT ck_notifications_typed_event CHECK(
    event_type = 'LEGACY_QUARANTINED'
    OR (event_type = 'ACCOUNT_NOTICE' AND category = 'account' AND audience = 'PERSONAL'
        AND booking_id IS NULL AND ticket_id IS NULL AND station_id IS NULL AND refund_id IS NULL AND escalation_id IS NULL)
    OR (event_type = 'TICKET_CREATED' AND category = 'ticket'
        AND ((audience = 'ADMIN' AND station_id IS NULL) OR (audience = 'OWNER' AND station_id IS NOT NULL))
        AND ticket_id IS NOT NULL AND escalation_id IS NULL)
    OR (event_type = 'TICKET_RESOLVED' AND category = 'ticket' AND audience = 'PERSONAL' AND ticket_id IS NOT NULL)
    OR (event_type = 'TICKET_ASSIGNED' AND category = 'ticket'
        AND ((audience IN ('OWNER','STAFF') AND station_id IS NOT NULL)
             OR (audience = 'ADMIN' AND station_id IS NULL)
             OR (audience = 'PERSONAL' AND ticket_id IS NOT NULL))
        AND ticket_id IS NOT NULL)
    OR (event_type = 'CASE_ESCALATED' AND category = 'ticket' AND audience = 'ADMIN'
        AND ticket_id IS NOT NULL AND escalation_id IS NOT NULL)
    OR (event_type IN ('BOOKING_CONFIRMED','BOOKING_CANCELLED','BOOKING_NO_SHOW','BOOKING_COMPLETED','BOOKING_REMINDER')
        AND category = 'booking' AND audience = 'DRIVER' AND booking_id IS NOT NULL AND station_id IS NOT NULL
        AND (event_type <> 'BOOKING_REMINDER' OR expires_at IS NOT NULL))
    OR (event_type = 'RESPONSIBILITY_DECIDED' AND category = 'finance' AND audience IN ('DRIVER','OWNER','ADMIN')
        AND booking_id IS NOT NULL AND station_id IS NOT NULL AND (audience <> 'ADMIN' OR escalation_id IS NOT NULL))
    OR (event_type IN ('REFUND_PENDING','REFUND_ATTEMPT_FAILED','REFUND_SUCCEEDED') AND category = 'finance'
        AND audience IN ('DRIVER','OWNER','ADMIN') AND booking_id IS NOT NULL AND station_id IS NOT NULL
        AND refund_id IS NOT NULL AND (audience <> 'ADMIN' OR escalation_id IS NOT NULL))
);
