-- BKG-052: lifecycle deadlines, immutable workflow audit and durable in-app notices.
-- Legacy RESOLVED rows intentionally retain a NULL deadline: their resolution
-- time and delivery of a notice cannot be inferred from updated_at.
ALTER TABLE support_tickets
    ADD COLUMN resolved_at timestamptz,
    ADD COLUMN auto_close_at timestamptz,
    ADD COLUMN close_reason varchar(40),
    ADD COLUMN resolution_cycle integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_support_ticket_close_reason
        CHECK (close_reason IS NULL OR close_reason IN
            ('REPORTER_CONFIRMED', 'AUTO_CLOSED_NO_RESPONSE')),
    ADD CONSTRAINT ck_support_ticket_resolution_cycle
        CHECK (resolution_cycle >= 0),
    ADD CONSTRAINT ck_support_ticket_deadline
        CHECK (auto_close_at IS NULL OR
            (status = 'RESOLVED' AND resolved_at IS NOT NULL AND
             auto_close_at = resolved_at + interval '10 days'));

CREATE INDEX idx_support_tickets_due_auto_close
    ON support_tickets (auto_close_at, id)
    WHERE status = 'RESOLVED' AND auto_close_at IS NOT NULL;

CREATE TABLE ticket_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL REFERENCES support_tickets(id) ON DELETE RESTRICT,
    actor_id uuid REFERENCES user_profile(id) ON DELETE RESTRICT,
    actor_kind varchar(20) NOT NULL,
    event_type varchar(40) NOT NULL,
    from_status varchar(20),
    to_status varchar(20),
    from_handler_id uuid REFERENCES user_profile(id) ON DELETE RESTRICT,
    to_handler_id uuid REFERENCES user_profile(id) ON DELETE RESTRICT,
    to_handler_kind varchar(20),
    resolution_cycle integer NOT NULL,
    reason varchar(2000),
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_ticket_events_actor_kind CHECK
        (actor_kind IN ('REPORTER', 'OWNER', 'STAFF', 'ADMIN', 'SYSTEM')),
    CONSTRAINT ck_ticket_events_system_actor CHECK
        ((actor_kind = 'SYSTEM' AND actor_id IS NULL) OR
         (actor_kind <> 'SYSTEM' AND actor_id IS NOT NULL)),
    CONSTRAINT ck_ticket_events_type CHECK
        (event_type IN ('CLAIMED', 'ASSIGNED', 'REASSIGNED', 'RESOLVED',
                        'REPORTER_CONFIRMED', 'REPORTER_CONTINUED',
                        'HANDLER_CONTINUED', 'AUTO_CLOSED_NO_RESPONSE',
                        'HANDLER_REVOKED')),
    CONSTRAINT ck_ticket_events_cycle CHECK (resolution_cycle >= 0),
    CONSTRAINT ck_ticket_events_to_handler_kind CHECK
        (to_handler_kind IS NULL OR to_handler_kind IN ('OWNER', 'STAFF', 'ADMIN')),
    CONSTRAINT ck_ticket_events_reason CHECK
        (reason IS NULL OR btrim(reason) <> '')
);

CREATE INDEX idx_ticket_events_ticket_created
    ON ticket_events (ticket_id, created_at, id);
CREATE INDEX idx_ticket_events_actor_type_created
    ON ticket_events (actor_id, event_type, created_at);
CREATE UNIQUE INDEX ux_ticket_events_auto_close_cycle
    ON ticket_events (ticket_id, resolution_cycle, event_type)
    WHERE event_type = 'AUTO_CLOSED_NO_RESPONSE';

CREATE TRIGGER trg_ticket_events_append_only
    BEFORE UPDATE OR DELETE ON ticket_events
    FOR EACH ROW EXECUTE FUNCTION reject_ticket_append_only_mutation();

CREATE TABLE app_notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    category varchar(30) NOT NULL DEFAULT 'ticket',
    event_key varchar(160) NOT NULL UNIQUE,
    title varchar(200) NOT NULL,
    body varchar(2000) NOT NULL,
    action_url varchar(500) NOT NULL,
    created_at timestamptz NOT NULL,
    read_at timestamptz,
    CONSTRAINT ck_app_notifications_category CHECK (category = 'ticket'),
    CONSTRAINT ck_app_notifications_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_app_notifications_body CHECK (btrim(body) <> ''),
    CONSTRAINT ck_app_notifications_action_url CHECK (btrim(action_url) <> ''),
    CONSTRAINT ck_app_notifications_read_at CHECK
        (read_at IS NULL OR read_at >= created_at)
);

CREATE INDEX idx_app_notifications_recipient_created
    ON app_notifications (recipient_id, created_at DESC, id DESC);
CREATE INDEX idx_app_notifications_unread
    ON app_notifications (recipient_id, created_at DESC, id DESC)
    WHERE read_at IS NULL;
