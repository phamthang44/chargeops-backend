-- BKG-053: keep incident hold while a reporter dispute is awaiting a newer finding.
CREATE OR REPLACE VIEW v_owner_booking_financials AS
WITH receipt_totals AS (
    SELECT payment_id,
           sum(amount) AS gross_collected_amount,
           sum(amount) FILTER (WHERE application_classification = 'APPLIED') AS applied_amount
      FROM payment_transactions WHERE payment_id IS NOT NULL GROUP BY payment_id
), refund_totals AS (
    SELECT booking_id,
           sum(amount) FILTER (WHERE status = 'PENDING') AS refund_pending_amount,
           sum(amount) FILTER (WHERE status = 'SUCCEEDED') AS refund_succeeded_amount,
           count(*) AS refund_count
      FROM refunds GROUP BY booking_id
), payout_totals AS (
    SELECT i.booking_id,
           sum(i.amount) FILTER (WHERE i.status = 'PENDING' AND p.status = 'PENDING') AS payout_pending_amount,
           sum(i.amount) FILTER (WHERE i.status = 'SUCCEEDED' AND p.status = 'SUCCEEDED') AS paid_to_owner_amount,
           count(*) FILTER (WHERE i.status IN ('PENDING', 'SUCCEEDED')) AS reserved_count
      FROM owner_payout_items i JOIN owner_payouts p ON p.id = i.payout_id GROUP BY i.booking_id
), adjustment_totals AS (
    SELECT booking_id, sum(-amount) FILTER (WHERE amount < 0) AS adjustment_due_amount
      FROM owner_adjustments WHERE booking_id IS NOT NULL GROUP BY booking_id
), booking_financials AS (
    SELECT b.id AS booking_id, b.booking_code, b.status AS booking_status, b.cancellation_reason,
           s.id AS station_id, b.financial_owner_id AS owner_id, p.id AS payment_id, p.status AS payment_status,
           p.environment, p.currency, p.amount AS expected_package_amount, p.needs_reconciliation,
           coalesce(rt.gross_collected_amount, 0::numeric) AS gross_collected_amount,
           coalesce(rt.applied_amount, 0::numeric) AS applied_amount,
           coalesce(rf.refund_pending_amount, 0::numeric) AS refund_pending_amount,
           coalesce(rf.refund_succeeded_amount, 0::numeric) AS refund_succeeded_amount,
           coalesce(rf.refund_count, 0) AS refund_count,
           coalesce(po.payout_pending_amount, 0::numeric) AS payout_pending_amount,
           coalesce(po.paid_to_owner_amount, 0::numeric) AS paid_to_owner_amount,
           coalesce(po.reserved_count, 0) AS reserved_count,
           coalesce(ad.adjustment_due_amount, 0::numeric) AS adjustment_due_amount,
           EXISTS (
               SELECT 1 FROM support_tickets t
                WHERE t.booking_id = b.id AND t.category = 'CHARGING_ISSUE'
                  AND (coalesce((
                      SELECT f.conclusion FROM ticket_findings f
                       WHERE f.ticket_id = t.id AND f.booking_id = b.id
                       ORDER BY f.recorded_at DESC, f.id DESC LIMIT 1
                  ), 'UNRESOLVED') <> 'NOT_STATION_FAILURE'
                  OR EXISTS (
                      SELECT 1 FROM ticket_events e
                       WHERE e.ticket_id = t.id AND e.event_type = 'REPORTER_CONTINUED'
                         AND e.created_at > coalesce((
                             SELECT max(f.recorded_at) FROM ticket_findings f
                              WHERE f.ticket_id = t.id AND f.booking_id = b.id
                         ), '-infinity'::timestamptz)
                  ))
           ) AS incident_held
      FROM bookings b
      JOIN payments p ON p.booking_id = b.id AND p.environment IN ('SIMULATOR', 'TEST', 'LIVE')
      JOIN connectors c ON c.id = b.connector_id
      JOIN charge_points cp ON cp.id = c.charge_point_id
      JOIN stations s ON s.id = cp.station_id
      LEFT JOIN receipt_totals rt ON rt.payment_id = p.id
      LEFT JOIN refund_totals rf ON rf.booking_id = b.id
      LEFT JOIN payout_totals po ON po.booking_id = b.id
      LEFT JOIN adjustment_totals ad ON ad.booking_id = b.id
)
SELECT f.booking_id, f.booking_code, f.owner_id, f.station_id, f.payment_id,
       f.environment, f.currency, f.expected_package_amount, f.gross_collected_amount,
       f.applied_amount, f.refund_pending_amount, f.refund_succeeded_amount,
       f.payout_pending_amount, f.paid_to_owner_amount, f.adjustment_due_amount,
       f.incident_held,
       (f.booking_status = 'COMPLETED' OR
        (f.booking_status = 'CANCELLED' AND f.cancellation_reason IN ('NO_SHOW', 'DRIVER_CANCELLED')))
       AND f.payment_status = 'PAID' AND f.currency = 'VND'
       AND NOT f.needs_reconciliation AND f.applied_amount = f.expected_package_amount
       AND f.refund_count = 0 AND f.reserved_count = 0 AND NOT f.incident_held
       AS is_eligible_for_payout,
       CASE WHEN
           (f.booking_status = 'COMPLETED' OR
            (f.booking_status = 'CANCELLED' AND f.cancellation_reason IN ('NO_SHOW', 'DRIVER_CANCELLED')))
           AND f.payment_status = 'PAID' AND f.currency = 'VND'
           AND NOT f.needs_reconciliation AND f.applied_amount = f.expected_package_amount
           AND f.refund_count = 0 AND f.reserved_count = 0 AND NOT f.incident_held
           THEN f.applied_amount ELSE 0::numeric END AS eligible_amount
  FROM booking_financials f;
