-- Read-only reporting source. UNION ALL avoids multiplying receipts by refunds.
-- Ownership is the immutable financial snapshot, never a client-supplied owner.
CREATE VIEW v_owner_simulator_cash_events AS
SELECT b.financial_owner_id AS owner_id, s.id AS station_id,
       s.station_code, s.name AS station_name, pt.received_at AS occurred_at,
       pt.amount AS gross_vnd, 0::numeric AS refunded_vnd
FROM payment_transactions pt
JOIN payments p ON p.id = pt.payment_id
JOIN bookings b ON b.id = p.booking_id
JOIN connectors c ON c.id = b.connector_id
JOIN charge_points cp ON cp.id = c.charge_point_id
JOIN stations s ON s.id = cp.station_id
WHERE p.environment = 'SIMULATOR' AND p.status IN ('PAID', 'REFUNDED')
  AND pt.application_classification = 'APPLIED'
UNION ALL
SELECT b.financial_owner_id, s.id, s.station_code, s.name, r.completed_at,
       0::numeric, r.amount
FROM refunds r
JOIN payments p ON p.id = r.payment_id
JOIN bookings b ON b.id = p.booking_id
JOIN connectors c ON c.id = b.connector_id
JOIN charge_points cp ON cp.id = c.charge_point_id
JOIN stations s ON s.id = cp.station_id
WHERE p.environment = 'SIMULATOR' AND p.status IN ('PAID', 'REFUNDED')
  AND r.status = 'SUCCEEDED';

COMMENT ON VIEW v_owner_simulator_cash_events IS
  'Simulator cash flow: APPLIED receipts at received_at; SUCCEEDED refunds at completed_at. Consumers MUST filter owner_id. Not profit or payout.';
