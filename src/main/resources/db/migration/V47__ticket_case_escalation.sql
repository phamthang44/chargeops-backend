CREATE TABLE ticket_escalations (
    id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL REFERENCES support_tickets(id),
    requested_by uuid NOT NULL REFERENCES user_profile(id),
    requested_at timestamptz NOT NULL,
    reason varchar(2000) NOT NULL,
    CONSTRAINT ux_ticket_escalations_ticket UNIQUE (ticket_id),
    CONSTRAINT ck_ticket_escalations_reason CHECK (length(btrim(reason)) > 0)
);
CREATE INDEX idx_ticket_escalations_requested_at ON ticket_escalations(requested_at DESC, id DESC);
