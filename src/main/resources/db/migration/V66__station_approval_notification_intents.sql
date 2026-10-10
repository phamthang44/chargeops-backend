-- WS-05: station approval moderation realtime notifications.
-- New category 'station', action OPEN_STATION, and four event types:
-- STATION_SUBMITTED (ADMIN) / STATION_APPROVED|STATION_REJECTED|STATION_CHANGES_REQUESTED (OWNER).
-- Also backfills TICKET_CLOSED / TICKET_REOPENED into the event-type whitelist
-- (they exist in the Java enum since the ticket workflow workstream but were never added here).

ALTER TABLE app_notifications DROP CONSTRAINT ck_notifications_event_type;
ALTER TABLE app_notifications ADD CONSTRAINT ck_notifications_event_type CHECK(event_type IN (
    'LEGACY_QUARANTINED','TICKET_CREATED','TICKET_RESOLVED','TICKET_ASSIGNED','TICKET_CLOSED','TICKET_REOPENED','CASE_ESCALATED',
    'BOOKING_CONFIRMED','BOOKING_CANCELLED','BOOKING_NO_SHOW','BOOKING_COMPLETED','BOOKING_REMINDER',
    'RESPONSIBILITY_DECIDED','REFUND_PENDING','REFUND_ATTEMPT_FAILED','REFUND_SUCCEEDED','ACCOUNT_NOTICE',
    'STATION_SUBMITTED','STATION_APPROVED','STATION_REJECTED','STATION_CHANGES_REQUESTED'));

ALTER TABLE app_notifications DROP CONSTRAINT ck_notifications_category;
ALTER TABLE app_notifications ADD CONSTRAINT ck_notifications_category
    CHECK(category IN ('ticket','booking','finance','account','station'));

ALTER TABLE app_notifications DROP CONSTRAINT ck_notifications_action;
ALTER TABLE app_notifications ADD CONSTRAINT ck_notifications_action CHECK(
    action_type = 'NONE'
    OR (action_type = 'OPEN_BOOKING' AND booking_id IS NOT NULL)
    OR (action_type = 'OPEN_TICKET' AND ticket_id IS NOT NULL)
    OR (action_type = 'OPEN_REFUND' AND refund_id IS NOT NULL)
    OR (action_type = 'OPEN_CASE' AND ticket_id IS NOT NULL AND escalation_id IS NOT NULL)
    OR (action_type = 'OPEN_STATION' AND station_id IS NOT NULL));

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
    OR (event_type IN ('TICKET_CLOSED','TICKET_REOPENED') AND category = 'ticket'
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
    OR (event_type = 'STATION_SUBMITTED' AND category = 'station' AND audience = 'ADMIN'
        AND station_id IS NOT NULL AND booking_id IS NULL AND ticket_id IS NULL
        AND refund_id IS NULL AND escalation_id IS NULL)
    OR (event_type IN ('STATION_APPROVED','STATION_REJECTED','STATION_CHANGES_REQUESTED')
        AND category = 'station' AND audience = 'OWNER'
        AND station_id IS NOT NULL AND booking_id IS NULL AND ticket_id IS NULL
        AND refund_id IS NULL AND escalation_id IS NULL)
);
