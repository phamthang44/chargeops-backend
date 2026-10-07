-- N0-BE (#57): preserve legacy rows; only verified reporter notices become visible.
ALTER TABLE app_notifications ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE app_notifications
    ADD COLUMN audience varchar(20) NOT NULL DEFAULT 'PERSONAL',
    ADD COLUMN event_type varchar(40) NOT NULL DEFAULT 'LEGACY_QUARANTINED',
    ADD COLUMN source_event_key varchar(160),
    ADD COLUMN booking_id uuid REFERENCES bookings(id) ON DELETE RESTRICT,
    ADD COLUMN ticket_id uuid REFERENCES support_tickets(id) ON DELETE RESTRICT,
    ADD COLUMN escalation_id uuid REFERENCES ticket_escalations(id) ON DELETE RESTRICT,
    ADD COLUMN station_id uuid REFERENCES stations(id) ON DELETE RESTRICT,
    ADD COLUMN refund_id uuid REFERENCES refunds(id) ON DELETE RESTRICT,
    ADD COLUMN action_type varchar(30) NOT NULL DEFAULT 'NONE',
    ADD COLUMN dismissed_at timestamptz,
    ADD COLUMN expires_at timestamptz;

UPDATE app_notifications SET source_event_key = event_key;
UPDATE app_notifications n
SET event_type = 'TICKET_RESOLVED', ticket_id = t.id, station_id = t.station_id,
    booking_id = t.booking_id, action_type = 'OPEN_TICKET', action_url = '/tickets/' || t.id
FROM support_tickets t
WHERE n.recipient_id = t.reporter_id
  AND lower(split_part(n.event_key, ':', 1)) = t.id::text
  AND n.event_key ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9]+:RESOLVED$'
  AND CASE WHEN n.event_key ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9]+:RESOLVED$'
        THEN split_part(n.event_key, ':', 2)::numeric END BETWEEN 0 AND t.resolution_cycle;

ALTER TABLE app_notifications
    ALTER COLUMN source_event_key SET NOT NULL,
    DROP CONSTRAINT app_notifications_event_key_key,
    DROP CONSTRAINT ck_app_notifications_category,
    ADD CONSTRAINT ux_notifications_recipient_audience_source UNIQUE(recipient_id, audience, source_event_key),
    ADD CONSTRAINT ck_notifications_audience CHECK(audience IN ('PERSONAL','DRIVER','OWNER','STAFF','ADMIN')),
    ADD CONSTRAINT ck_notifications_category CHECK(category IN ('ticket','booking','finance','account')),
    ADD CONSTRAINT ck_notifications_event_type CHECK(event_type IN (
        'LEGACY_QUARANTINED','TICKET_RESOLVED','TICKET_ASSIGNED','CASE_ESCALATED',
        'BOOKING_CONFIRMED','BOOKING_CANCELLED','BOOKING_NO_SHOW','BOOKING_COMPLETED','BOOKING_REMINDER',
        'RESPONSIBILITY_DECIDED','REFUND_PENDING','REFUND_ATTEMPT_FAILED','REFUND_SUCCEEDED','ACCOUNT_NOTICE')),
    ADD CONSTRAINT ck_notifications_source CHECK(btrim(source_event_key) <> ''),
    ADD CONSTRAINT ck_notifications_dismissed CHECK(dismissed_at IS NULL OR dismissed_at >= created_at),
    ADD CONSTRAINT ck_notifications_expiry CHECK(expires_at IS NULL OR expires_at > created_at),
    ADD CONSTRAINT ck_notifications_action CHECK(
        action_type = 'NONE'
        OR (action_type = 'OPEN_BOOKING' AND booking_id IS NOT NULL)
        OR (action_type = 'OPEN_TICKET' AND ticket_id IS NOT NULL)
        OR (action_type = 'OPEN_REFUND' AND refund_id IS NOT NULL)
        OR (action_type = 'OPEN_CASE' AND ticket_id IS NOT NULL AND escalation_id IS NOT NULL)),
    ADD CONSTRAINT ck_notifications_typed_event CHECK(
        event_type = 'LEGACY_QUARANTINED'
        OR (event_type = 'ACCOUNT_NOTICE' AND category = 'account' AND audience = 'PERSONAL'
            AND booking_id IS NULL AND ticket_id IS NULL AND station_id IS NULL AND refund_id IS NULL AND escalation_id IS NULL)
        OR (event_type = 'TICKET_RESOLVED' AND category = 'ticket' AND audience = 'PERSONAL' AND ticket_id IS NOT NULL)
        OR (event_type = 'TICKET_ASSIGNED' AND category = 'ticket' AND audience IN ('OWNER','STAFF')
            AND ticket_id IS NOT NULL AND station_id IS NOT NULL)
        OR (event_type = 'CASE_ESCALATED' AND category = 'ticket' AND audience = 'ADMIN'
            AND ticket_id IS NOT NULL AND escalation_id IS NOT NULL)
        OR (event_type IN ('BOOKING_CONFIRMED','BOOKING_CANCELLED','BOOKING_NO_SHOW','BOOKING_COMPLETED','BOOKING_REMINDER')
            AND category = 'booking' AND audience = 'DRIVER' AND booking_id IS NOT NULL AND station_id IS NOT NULL
            AND (event_type <> 'BOOKING_REMINDER' OR expires_at IS NOT NULL))
        OR (event_type = 'RESPONSIBILITY_DECIDED' AND category = 'finance' AND audience IN ('DRIVER','OWNER','ADMIN')
            AND booking_id IS NOT NULL AND station_id IS NOT NULL AND (audience <> 'ADMIN' OR escalation_id IS NOT NULL))
        OR (event_type IN ('REFUND_PENDING','REFUND_ATTEMPT_FAILED','REFUND_SUCCEEDED') AND category = 'finance'
            AND audience IN ('DRIVER','OWNER','ADMIN') AND booking_id IS NOT NULL AND station_id IS NOT NULL
            AND refund_id IS NOT NULL AND (audience <> 'ADMIN' OR escalation_id IS NOT NULL)));

CREATE INDEX idx_notifications_visible_view ON app_notifications(recipient_id, audience, created_at DESC, id DESC)
    WHERE dismissed_at IS NULL AND event_type <> 'LEGACY_QUARANTINED';
CREATE INDEX idx_notifications_visible_unread ON app_notifications(recipient_id, audience, created_at DESC, id DESC)
    WHERE read_at IS NULL AND dismissed_at IS NULL AND event_type <> 'LEGACY_QUARANTINED';
