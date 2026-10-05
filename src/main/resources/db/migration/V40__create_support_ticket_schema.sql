-- BKG-049: support ticket persistence foundation.
-- Ticket routing and public commands are deliberately implemented by later tasks.

CREATE TABLE support_tickets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_code varchar(32) NOT NULL,
    category varchar(30) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    priority varchar(10) NOT NULL DEFAULT 'MEDIUM',
    reporter_id uuid NOT NULL,
    assigned_handler_id uuid,
    station_id uuid,
    booking_id uuid,
    subject varchar(160) NOT NULL,
    description varchar(2000) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,

    CONSTRAINT ux_support_tickets_code UNIQUE (ticket_code),
    CONSTRAINT fk_support_tickets_reporter
        FOREIGN KEY (reporter_id) REFERENCES user_profile(id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_tickets_handler
        FOREIGN KEY (assigned_handler_id) REFERENCES user_profile(id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_tickets_station
        FOREIGN KEY (station_id) REFERENCES stations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_tickets_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_tickets_code
        CHECK (ticket_code ~ '^TKT-[0-9]{8}-[0-9]{4,}$'),
    CONSTRAINT ck_support_tickets_category
        CHECK (category IN ('CHARGING_ISSUE', 'BOOKING', 'PAYMENT', 'ACCOUNT', 'OTHER')),
    CONSTRAINT ck_support_tickets_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_support_tickets_priority
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_support_tickets_subject CHECK (btrim(subject) <> ''),
    CONSTRAINT ck_support_tickets_description CHECK (btrim(description) <> ''),
    CONSTRAINT ck_support_tickets_version CHECK (version >= 0)
);

CREATE INDEX idx_support_tickets_reporter_created
    ON support_tickets(reporter_id, created_at DESC, id DESC);
CREATE INDEX idx_support_tickets_station_status
    ON support_tickets(station_id, status, created_at DESC, id DESC);
CREATE INDEX idx_support_tickets_handler_status
    ON support_tickets(assigned_handler_id, status, created_at DESC, id DESC);
CREATE INDEX idx_support_tickets_category_status
    ON support_tickets(category, status, created_at DESC, id DESC);
CREATE INDEX idx_support_tickets_booking
    ON support_tickets(booking_id) WHERE booking_id IS NOT NULL;

CREATE TABLE ticket_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL,
    author_id uuid NOT NULL,
    author_kind varchar(20) NOT NULL,
    body varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL,

    CONSTRAINT fk_ticket_messages_ticket
        FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_messages_author
        FOREIGN KEY (author_id) REFERENCES user_profile(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ticket_messages_author_kind
        CHECK (author_kind IN ('REPORTER', 'OWNER', 'STAFF', 'ADMIN')),
    CONSTRAINT ck_ticket_messages_body CHECK (btrim(body) <> '')
);

CREATE INDEX idx_ticket_messages_ticket_created
    ON ticket_messages(ticket_id, created_at, id);

CREATE TABLE ticket_findings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL,
    booking_id uuid NOT NULL,
    conclusion varchar(30) NOT NULL,
    affected_at timestamptz NOT NULL,
    reason varchar(2000) NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL,

    CONSTRAINT fk_ticket_findings_ticket
        FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_findings_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_findings_actor
        FOREIGN KEY (recorded_by) REFERENCES user_profile(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ticket_findings_conclusion
        CHECK (conclusion IN ('STATION_FAILURE', 'NOT_STATION_FAILURE')),
    CONSTRAINT ck_ticket_findings_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_ticket_findings_time CHECK (affected_at <= recorded_at)
);

CREATE INDEX idx_ticket_findings_ticket_recorded
    ON ticket_findings(ticket_id, recorded_at, id);
CREATE INDEX idx_ticket_findings_booking_conclusion
    ON ticket_findings(booking_id, conclusion, recorded_at, id);

CREATE OR REPLACE FUNCTION reject_ticket_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is forbidden', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_ticket_messages_append_only
    BEFORE UPDATE OR DELETE ON ticket_messages
    FOR EACH ROW EXECUTE FUNCTION reject_ticket_append_only_mutation();

CREATE TRIGGER trg_ticket_findings_append_only
    BEFORE UPDATE OR DELETE ON ticket_findings
    FOR EACH ROW EXECUTE FUNCTION reject_ticket_append_only_mutation();

COMMENT ON TABLE support_tickets IS
    'Support ticket aggregate root. Status alone never grants or records a refund.';
COMMENT ON TABLE ticket_messages IS
    'Append-only support conversation; update and delete are rejected at the database boundary.';
COMMENT ON TABLE ticket_findings IS
    'Append-only station-fault conclusions, independent from ticket lifecycle status.';
