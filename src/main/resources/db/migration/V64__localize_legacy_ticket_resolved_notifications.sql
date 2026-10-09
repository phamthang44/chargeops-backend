-- New resolutions already use i18n keys. Recover legacy copy from the immutable
-- RESOLVED event for the notice's own cycle, never from the ticket's latest cycle.
-- Leave unverifiable legacy notices and already-localized fields untouched.
WITH resolution_copy AS (
    SELECT n.id, t.ticket_code, e.reason,
           e.created_at + interval '240 hours' AS auto_close_at
    FROM app_notifications n
    JOIN support_tickets t ON t.id = n.ticket_id AND t.reporter_id = n.recipient_id
    JOIN ticket_events e ON e.ticket_id = t.id
        AND e.event_type = 'RESOLVED'
        AND n.source_event_key = t.id::text || ':' || e.resolution_cycle::text || ':RESOLVED'
    WHERE n.event_type = 'TICKET_RESOLVED'
        AND n.category = 'ticket'
        AND e.reason IS NOT NULL AND btrim(e.reason) <> ''
        AND (n.title NOT LIKE 'notification.%' OR n.body NOT LIKE 'notification.%')
        AND NOT EXISTS (
            SELECT 1 FROM ticket_events duplicate
            WHERE duplicate.ticket_id = e.ticket_id
                AND duplicate.event_type = 'RESOLVED'
                AND duplicate.resolution_cycle = e.resolution_cycle
                AND duplicate.id <> e.id
        )
)
UPDATE app_notifications n
SET title = CASE WHEN n.title LIKE 'notification.%' THEN n.title ELSE
        'notification.ticket.resolved.title|' || jsonb_build_object('code', r.ticket_code)::text END,
    body = CASE WHEN n.body LIKE 'notification.%' THEN n.body ELSE
        'notification.ticket.resolved.body|' || jsonb_build_object(
            'code', r.ticket_code,
            'result', left(btrim(r.reason), 800),
            'autoCloseAt', to_char(r.auto_close_at AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))::text END
FROM resolution_copy r
WHERE n.id = r.id;
