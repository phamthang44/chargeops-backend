\set ON_ERROR_STOP on

-- BKG-069 — scoped reset for seed-bkg-069-demo.sql
--
-- Removes ONLY the rows created by seed-bkg-069-demo.sql (deterministic
-- 'bkg069-' UUIDs and the three ST-19xx station codes). It never touches
-- application data and it never resets or recreates the database.
--
-- The platform keeps several tables append-only behind database triggers
-- (booking_status_history, ticket_messages, ticket_events, ticket_findings,
-- booking_service_failure_decisions, incident evidence, payout history). Those
-- rows cannot be deleted by design, so this script refuses to run once the
-- demo scenarios have written any of them: restore the snapshot taken right
-- after seeding instead (see the BKG-069 runbook).

BEGIN;

-- ---------------------------------------------------------------------------
-- 0. Refuse to run when runtime output already references the demo fixture
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_blockers text := '';
    v_count    integer;
    v_booking  uuid := md5('bkg069-booking-own-001')::uuid;
    v_ticket   uuid := md5('bkg069-ticket-001')::uuid;
    v_station_1 uuid := md5('bkg069-station-1901')::uuid;
    v_station_2 uuid := md5('bkg069-station-1902')::uuid;
    v_station_3 uuid := md5('bkg069-station-1903')::uuid;
    v_payment  uuid := md5('bkg069-payment-own-001')::uuid;
BEGIN
    SELECT count(*) INTO v_count FROM booking_status_history WHERE booking_id = v_booking;
    IF v_count > 0 THEN v_blockers := v_blockers || format(' booking_status_history=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM booking_commands WHERE booking_id = v_booking;
    IF v_count > 0 THEN v_blockers := v_blockers || format(' booking_commands=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM refunds WHERE booking_id = v_booking;
    IF v_count > 0 THEN v_blockers := v_blockers || format(' refunds=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM booking_service_failure_decisions
     WHERE booking_id = v_booking OR ticket_id = v_ticket
        OR station_id IN (v_station_1, v_station_2, v_station_3);
    IF v_count > 0 THEN v_blockers := v_blockers || format(' booking_service_failure_decisions=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM incident_bookings WHERE booking_id = v_booking;
    IF v_count > 0 THEN v_blockers := v_blockers || format(' incident_bookings=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM app_notifications
     WHERE booking_id = v_booking
        OR ticket_id = v_ticket
        OR station_id IN (v_station_1, v_station_2, v_station_3);
    IF v_count > 0 THEN v_blockers := v_blockers || format(' app_notifications=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM owner_adjustments
     WHERE booking_id = v_booking OR owner_id IN (
            md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-b')::uuid);
    IF v_count > 0 THEN v_blockers := v_blockers || format(' owner_adjustments=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM owner_payout_items WHERE booking_id = v_booking;
    IF v_count > 0 THEN v_blockers := v_blockers || format(' owner_payout_items=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM ticket_messages WHERE ticket_id = v_ticket;
    IF v_count > 0 THEN v_blockers := v_blockers || format(' ticket_messages=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM ticket_events WHERE ticket_id = v_ticket;
    IF v_count > 0 THEN v_blockers := v_blockers || format(' ticket_events=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM ticket_findings WHERE ticket_id = v_ticket OR booking_id = v_booking;
    IF v_count > 0 THEN v_blockers := v_blockers || format(' ticket_findings=%s', v_count); END IF;

    SELECT count(*) INTO v_count FROM connector_incidents
     WHERE connector_id IN (
            SELECT c.id FROM connectors c
             WHERE c.id IN (md5('bkg069-conn-1901-01-1')::uuid, md5('bkg069-conn-1901-01-2')::uuid,
                            md5('bkg069-conn-1901-01-3')::uuid, md5('bkg069-conn-1902-01-1')::uuid,
                            md5('bkg069-conn-1903-01-1')::uuid));
    IF v_count > 0 THEN v_blockers := v_blockers || format(' connector_incidents=%s', v_count); END IF;

    IF v_blockers <> '' THEN
        RAISE EXCEPTION
            'BKG-069 reset refused: append-only/audit rows already reference the demo fixture:%. Restore the snapshot taken right after seed-bkg-069-demo.sql instead of deleting audit evidence.',
            v_blockers;
    END IF;

    -- Silence unused-variable warnings for the lookup helpers above.
    RAISE NOTICE 'BKG-069 reset precheck OK (stations %,%,%; payment %).',
        v_station_1, v_station_2, v_station_3, v_payment;
END $$;

-- ---------------------------------------------------------------------------
-- 1. Runtime rows that are deletable but were never written by the seed
-- ---------------------------------------------------------------------------
DELETE FROM notification_email_deliveries
 WHERE event_id IN (SELECT event_id FROM notification_outbox WHERE booking_id = md5('bkg069-booking-own-001')::uuid);

DELETE FROM notification_outbox
 WHERE booking_id = md5('bkg069-booking-own-001')::uuid;

DELETE FROM license_status_events
 WHERE license_id IN (
        SELECT id FROM licenses
         WHERE station_id IN (md5('bkg069-station-1901')::uuid,
                              md5('bkg069-station-1902')::uuid,
                              md5('bkg069-station-1903')::uuid));

-- ---------------------------------------------------------------------------
-- 2. Seed rows, children before parents
-- ---------------------------------------------------------------------------
DELETE FROM ticket_escalations WHERE ticket_id = md5('bkg069-ticket-001')::uuid;
DELETE FROM support_tickets    WHERE ticket_code = 'TKT-20261009-0690';

DELETE FROM payment_transactions WHERE payment_id = md5('bkg069-payment-own-001')::uuid;
DELETE FROM payments             WHERE booking_id = md5('bkg069-booking-own-001')::uuid;
DELETE FROM booking_price_lines  WHERE booking_id = md5('bkg069-booking-own-001')::uuid;
DELETE FROM bookings             WHERE booking_code = 'BKGB69OWN0001';

DELETE FROM connector_status_events
 WHERE connector_id IN (md5('bkg069-conn-1901-01-1')::uuid, md5('bkg069-conn-1901-01-2')::uuid,
                        md5('bkg069-conn-1901-01-3')::uuid, md5('bkg069-conn-1902-01-1')::uuid,
                        md5('bkg069-conn-1903-01-1')::uuid);
DELETE FROM connectors
 WHERE charge_point_id IN (md5('bkg069-cp-1901-01')::uuid, md5('bkg069-cp-1901-02')::uuid,
                           md5('bkg069-cp-1902-01')::uuid, md5('bkg069-cp-1903-01')::uuid);

DELETE FROM charge_point_status_events
 WHERE charge_point_id IN (md5('bkg069-cp-1901-01')::uuid, md5('bkg069-cp-1901-02')::uuid,
                           md5('bkg069-cp-1902-01')::uuid, md5('bkg069-cp-1903-01')::uuid);
DELETE FROM charge_points
 WHERE charge_point_code IN ('CP-B691-01', 'CP-B691-02', 'CP-B692-01', 'CP-B693-01');

DELETE FROM station_operating_periods
 WHERE schedule_id IN (md5('bkg069-sched-1901')::uuid, md5('bkg069-sched-1902')::uuid, md5('bkg069-sched-1903')::uuid);
DELETE FROM station_operating_schedules
 WHERE station_id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid);
DELETE FROM station_booking_settings
 WHERE station_id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid);
DELETE FROM tou_rates
 WHERE station_id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid);
DELETE FROM station_staff_assignments
 WHERE station_id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid);
DELETE FROM station_operational_status_events
 WHERE station_id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid);
DELETE FROM station_status_history
 WHERE station_id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid);
DELETE FROM licenses
 WHERE station_id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid);

DELETE FROM stations WHERE station_code IN ('ST-1901', 'ST-1902', 'ST-1903');

DELETE FROM user_profile WHERE email LIKE 'bkg069.%@example.test';

COMMIT;

-- ---------------------------------------------------------------------------
-- 3. Verification — every count must be zero
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_left integer;
BEGIN
    SELECT count(*) INTO v_left FROM user_profile WHERE email LIKE 'bkg069.%@example.test';
    IF v_left <> 0 THEN RAISE EXCEPTION 'BKG-069 reset incomplete: % demo users left', v_left; END IF;

    SELECT count(*) INTO v_left FROM stations WHERE station_code IN ('ST-1901','ST-1902','ST-1903');
    IF v_left <> 0 THEN RAISE EXCEPTION 'BKG-069 reset incomplete: % demo stations left', v_left; END IF;

    SELECT count(*) INTO v_left FROM connectors
     WHERE charge_point_id IN (md5('bkg069-cp-1901-01')::uuid, md5('bkg069-cp-1901-02')::uuid,
                               md5('bkg069-cp-1902-01')::uuid, md5('bkg069-cp-1903-01')::uuid);
    IF v_left <> 0 THEN RAISE EXCEPTION 'BKG-069 reset incomplete: % demo connectors left', v_left; END IF;

    SELECT count(*) INTO v_left FROM bookings WHERE booking_code = 'BKGB69OWN0001';
    IF v_left <> 0 THEN RAISE EXCEPTION 'BKG-069 reset incomplete: % demo bookings left', v_left; END IF;

    SELECT count(*) INTO v_left FROM support_tickets WHERE ticket_code = 'TKT-20261009-0690';
    IF v_left <> 0 THEN RAISE EXCEPTION 'BKG-069 reset incomplete: % demo tickets left', v_left; END IF;

    RAISE NOTICE 'BKG-069 reset verification OK: no demo rows remain.';
END $$;
