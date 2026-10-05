ALTER TABLE ticket_events
    DROP CONSTRAINT ck_ticket_events_type;

ALTER TABLE ticket_events
    ADD CONSTRAINT ck_ticket_events_type CHECK
        (event_type IN ('CLAIMED', 'ASSIGNED', 'REASSIGNED', 'RESOLVED',
                        'REPORTER_CONFIRMED', 'REPORTER_CONTINUED',
                        'HANDLER_CONTINUED', 'AUTO_CLOSED_NO_RESPONSE',
                        'HANDLER_REVOKED', 'RETURN_TO_STATION',
                        'CLOSE_SUPPORT_CASE'));

ALTER TABLE support_tickets
    DROP CONSTRAINT ck_support_ticket_close_reason;

ALTER TABLE support_tickets
    ADD CONSTRAINT ck_support_ticket_close_reason CHECK
        (close_reason IS NULL OR close_reason IN
            ('REPORTER_CONFIRMED', 'AUTO_CLOSED_NO_RESPONSE',
             'ADMIN_SUPPORT_CASE_CLOSED'));
