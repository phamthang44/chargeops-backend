-- Metadata-only rename: preserve historical true/false values and NOT NULL.
ALTER TABLE refunds RENAME COLUMN requires_admin_action TO requires_owner_action;
COMMENT ON COLUMN refunds.requires_owner_action IS
    'Owner retry is required after a failed refund attempt; not an Admin approval flag.';

-- Reuse the existing append-only event store without adding another table.
ALTER TABLE ticket_events DROP CONSTRAINT ck_ticket_events_type;
ALTER TABLE ticket_events ADD CONSTRAINT ck_ticket_events_type CHECK
    (event_type IN ('CLAIMED', 'ASSIGNED', 'REASSIGNED', 'RESOLVED',
        'REPORTER_CONFIRMED', 'REPORTER_CONTINUED', 'HANDLER_CONTINUED',
        'AUTO_CLOSED_NO_RESPONSE', 'HANDLER_REVOKED', 'RETURN_TO_STATION',
        'CLOSE_SUPPORT_CASE', 'REFUND_POLICY_CONTEXT',
        'TICKET_FINANCIAL_RESOLUTION', 'TICKET_REFUND_REFERENCES'));
