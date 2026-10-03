ALTER TABLE ticket_escalations
    ADD COLUMN resolved_at timestamptz,
    ADD COLUMN resolved_by uuid REFERENCES user_profile(id),
    ADD COLUMN resolution_type varchar(40),
    ADD COLUMN resolution_note varchar(2000),
    ADD COLUMN closure_reason varchar(50);

ALTER TABLE ticket_escalations DROP CONSTRAINT ux_ticket_escalations_ticket;
CREATE UNIQUE INDEX ux_ticket_escalations_active_ticket ON ticket_escalations(ticket_id)
    WHERE resolved_at IS NULL;
CREATE INDEX idx_ticket_escalations_ticket_history ON ticket_escalations(ticket_id, requested_at DESC, id DESC);

ALTER TABLE ticket_escalations ADD CONSTRAINT ck_ticket_escalations_review_complete CHECK (
    (resolved_at IS NULL AND resolved_by IS NULL AND resolution_type IS NULL
        AND resolution_note IS NULL AND closure_reason IS NULL)
    OR
    (resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND resolution_type IS NOT NULL
        AND length(btrim(resolution_note)) > 0
        AND ((resolution_type = 'RETURN_TO_STATION' AND closure_reason IS NULL)
          OR (resolution_type = 'CLOSE_SUPPORT_CASE' AND closure_reason IS NOT NULL)))
);
