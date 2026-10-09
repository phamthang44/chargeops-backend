\set ON_ERROR_STOP on

-- BKG-069 — scoped demo dataset for the Booking v4.9 acceptance demo.
--
-- Run manually against a DEMO database only, after Flyway has applied V60.
-- This is intentionally NOT a Flyway migration: demo data must never be
-- inserted automatically in any other environment.
--
-- Everything written here is addressed by deterministic UUIDs derived from the
-- 'bkg069-' namespace, so the seed is idempotent and reset-bkg-069-demo.sql can
-- remove exactly these rows and nothing else.
--
-- Fixtures produced by this file:
--   * Driver A/B, Owner A/B, Staff active/revoked, Admin          (user_profile)
--   * ST-1901 24/7 (Owner A), ST-1902 scheduled (Owner B),
--     ST-1903 ownership-change station (created by A, current owner B)
--   * charge points/connectors in AVAILABLE, IN_USE and OFFLINE (fault)
--   * one ACTIVE + one REVOKED staff assignment
--   * one escalated support case (ticket + active escalation)
--   * one ownership-change booking fixture: financial_owner_id = Owner A while
--     stations.owner_id = Owner B, with a SIMULATOR PAID payment and its
--     APPLIED receipt, so ledger/permission reads can be compared.
--
-- The ownership-change fixture is synthetic evidence data for scope checks. It
-- is not a product feature: the platform does not support transferring station
-- ownership (see 47-station-owner-immutability-and-no-deletion-scope.vi).

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Identities
-- ---------------------------------------------------------------------------
INSERT INTO user_profile (id, keycloak_id, email, display_name, phone, status, created_at, updated_at)
VALUES
    (md5('bkg069-user-driver-a')::uuid,      md5('bkg069-keycloak-driver-a')::uuid,      'bkg069.driver.a@example.test', 'Driver A (BKG-069)',      '0900000001', 'ACTIVE', now(), now()),
    (md5('bkg069-user-driver-b')::uuid,      md5('bkg069-keycloak-driver-b')::uuid,      'bkg069.driver.b@example.test', 'Driver B (BKG-069)',      '0900000002', 'ACTIVE', now(), now()),
    (md5('bkg069-user-owner-a')::uuid,       md5('bkg069-keycloak-owner-a')::uuid,       'bkg069.owner.a@example.test',  'Owner A (BKG-069)',       '0900000003', 'ACTIVE', now(), now()),
    (md5('bkg069-user-owner-b')::uuid,       md5('bkg069-keycloak-owner-b')::uuid,       'bkg069.owner.b@example.test',  'Owner B (BKG-069)',       '0900000004', 'ACTIVE', now(), now()),
    (md5('bkg069-user-staff-active')::uuid,  md5('bkg069-keycloak-staff-active')::uuid,  'bkg069.staff.active@example.test', 'Staff active (BKG-069)', '0900000005', 'ACTIVE', now(), now()),
    (md5('bkg069-user-staff-revoked')::uuid, md5('bkg069-keycloak-staff-revoked')::uuid, 'bkg069.staff.revoked@example.test', 'Staff revoked (BKG-069)', '0900000006', 'ACTIVE', now(), now()),
    (md5('bkg069-user-admin')::uuid,         md5('bkg069-keycloak-admin')::uuid,         'bkg069.admin@example.test',    'Admin (BKG-069)',         '0900000007', 'ACTIVE', now(), now())
ON CONFLICT (id) DO UPDATE SET
    keycloak_id  = EXCLUDED.keycloak_id,
    email        = EXCLUDED.email,
    display_name = EXCLUDED.display_name,
    phone        = EXCLUDED.phone,
    status       = EXCLUDED.status,
    deleted_at   = NULL,
    updated_at   = now();

-- ---------------------------------------------------------------------------
-- 2. Stations (ST-1903 is created under Owner A, ownership moves later)
-- ---------------------------------------------------------------------------
INSERT INTO stations (
    id, owner_id, station_code, name, description, address_line, location,
    ward_code, latitude, longitude, contact_phone, planned_charge_point_count,
    status, operational_status, operational_status_reason, version,
    created_at, updated_at, created_by, updated_by, deleted_at
)
VALUES
    (md5('bkg069-station-1901')::uuid, md5('bkg069-user-owner-a')::uuid, 'ST-1901',
     'B69 Tram 24/7 Ha Dong',
     'Tram demo BKG-069 mo cai 24/7, chuoc Owner A.',
     '210 Quang Trung, Ha Dong', NULL, '09556', 20.971500, 105.778200, '0900000003',
     2, 'ACTIVE', 'OPERATING', NULL, 0, now(), now(),
     md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-admin')::uuid, NULL),

    (md5('bkg069-station-1902')::uuid, md5('bkg069-user-owner-b')::uuid, 'ST-1902',
     'B69 Tram theo lich Cau Giay',
     'Tram demo BKG-069 mo cua theo lich 07:00-22:00, chuoc Owner B.',
     '88 Tran Thien Tang, Cau Giay', NULL, '00166', 21.033700, 105.785600, '0900000004',
     1, 'ACTIVE', 'OPERATING', NULL, 0, now(), now(),
     md5('bkg069-user-owner-b')::uuid, md5('bkg069-user-admin')::uuid, NULL),

    -- Created under Owner A; ownership moves to Owner B after the booking
    -- fixture below is written, so the booking keeps financial_owner_id = A.
    (md5('bkg069-station-1903')::uuid, md5('bkg069-user-owner-a')::uuid, 'ST-1903',
     'B69 Tram doi chu so huu',
     'Tram demo BKG-069 dung de kiem chung quyen khi owner trams khac owner tai chinh.',
     '15 Pham Hung, Nam Tu Liem', NULL, '00592', 21.016700, 105.781000, '0900000003',
     1, 'ACTIVE', 'OPERATING', NULL, 0, now(), now(),
     md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-admin')::uuid, NULL)
ON CONFLICT (id) DO UPDATE SET
    owner_id                 = EXCLUDED.owner_id,
    station_code             = EXCLUDED.station_code,
    name                     = EXCLUDED.name,
    description              = EXCLUDED.description,
    address_line             = EXCLUDED.address_line,
    ward_code                = EXCLUDED.ward_code,
    latitude                 = EXCLUDED.latitude,
    longitude                = EXCLUDED.longitude,
    contact_phone            = EXCLUDED.contact_phone,
    planned_charge_point_count = EXCLUDED.planned_charge_point_count,
    status                   = EXCLUDED.status,
    operational_status       = EXCLUDED.operational_status,
    deleted_at               = NULL,
    updated_at               = now();

-- ---------------------------------------------------------------------------
-- 3. Approval history for the three stations
-- ---------------------------------------------------------------------------
INSERT INTO station_status_history (id, station_id, event_type, from_status, to_status, reason, performed_by, performed_at)
SELECT
    md5('bkg069-ssh-' || s.station_code || '-submitted')::uuid,
    s.id, 'SUBMITTED', NULL, 'PENDING_APPROVAL', NULL,
    md5('bkg069-user-owner-a')::uuid, now() - interval '30 days'
FROM stations s
WHERE s.id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid)
ON CONFLICT (id) DO NOTHING;

INSERT INTO station_status_history (id, station_id, event_type, from_status, to_status, reason, performed_by, performed_at)
SELECT
    md5('bkg069-ssh-' || s.station_code || '-approved')::uuid,
    s.id, 'APPROVED', 'PENDING_APPROVAL', 'ACTIVE', NULL,
    md5('bkg069-user-admin')::uuid, now() - interval '29 days'
FROM stations s
WHERE s.id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid)
ON CONFLICT (id) DO NOTHING;

-- Operational history: ST-1902 was paused once and brought back, so the demo
-- shows a real OPERATING -> PAUSED -> OPERATING trail.
INSERT INTO station_operational_status_events (id, station_id, from_status, to_status, reason, performed_by, performed_at)
VALUES
    (md5('bkg069-sose-1902-pause')::uuid, md5('bkg069-station-1902')::uuid, 'OPERATING', 'PAUSED',
     'BKG-069: thay doi ke hoach bao tri', md5('bkg069-user-owner-b')::uuid, now() - interval '5 days'),
    (md5('bkg069-sose-1902-resume')::uuid, md5('bkg069-station-1902')::uuid, 'PAUSED', 'OPERATING',
     NULL, md5('bkg069-user-owner-b')::uuid, now() - interval '4 days')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Licenses (must stay ACTIVE for discovery eligibility)
-- ---------------------------------------------------------------------------
INSERT INTO licenses (
    id, station_id, owner_id, plan, fee_amount, start_at, expires_at, status,
    license_code, version, created_at, updated_at, created_by, updated_by
)
SELECT
    md5('bkg069-license-' || s.station_code)::uuid,
    s.id,
    s.owner_id,
    'YEARLY',
    3600000.00,
    now() - interval '60 days',
    now() + interval '305 days',
    'ACTIVE',
    'LIC-' || lpad((206900 + row_number() OVER (ORDER BY s.station_code))::text, 6, '0'),
    0, now(), now(),
    md5('bkg069-user-admin')::uuid,
    md5('bkg069-user-admin')::uuid
FROM stations s
WHERE s.id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid)
  AND s.deleted_at IS NULL
ON CONFLICT (id) DO UPDATE SET
    station_id = EXCLUDED.station_id,
    owner_id   = EXCLUDED.owner_id,
    status     = 'ACTIVE',
    updated_at = now();

-- ---------------------------------------------------------------------------
-- 5. Operating schedules: ST-1901 24/7, ST-1902 by schedule, ST-1903 24/7
-- ---------------------------------------------------------------------------
INSERT INTO station_operating_schedules (id, station_id, open_24_hours, effective_from, effective_to, created_at, updated_at, created_by, updated_by)
VALUES
    (md5('bkg069-sched-1901')::uuid, md5('bkg069-station-1901')::uuid, true,  now() - interval '30 days', NULL, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid),
    (md5('bkg069-sched-1902')::uuid, md5('bkg069-station-1902')::uuid, false, now() - interval '30 days', NULL, now(), now(), md5('bkg069-user-owner-b')::uuid, md5('bkg069-user-owner-b')::uuid),
    (md5('bkg069-sched-1903')::uuid, md5('bkg069-station-1903')::uuid, true,  now() - interval '30 days', NULL, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid)
ON CONFLICT (id) DO UPDATE SET
    station_id    = EXCLUDED.station_id,
    open_24_hours = EXCLUDED.open_24_hours,
    effective_to  = NULL,
    updated_at    = now();

-- Seven explicit periods for the schedule-based station; discovery is
-- fail-closed without them (V20 note).
INSERT INTO station_operating_periods (
    id, schedule_id, day_of_week, open_time, close_time, is_enabled,
    created_at, updated_at, created_by, updated_by, deleted_at
)
SELECT
    md5('bkg069-period-1902-' || d.day)::uuid,
    md5('bkg069-sched-1902')::uuid,
    d.day,
    time '07:00',
    time '22:00',
    true,
    now(), now(),
    md5('bkg069-user-owner-b')::uuid,
    md5('bkg069-user-owner-b')::uuid,
    NULL
FROM (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY'), ('SUNDAY')) AS d(day)
ON CONFLICT (id) DO UPDATE SET
    schedule_id = EXCLUDED.schedule_id,
    open_time   = EXCLUDED.open_time,
    close_time  = EXCLUDED.close_time,
    is_enabled  = true,
    deleted_at  = NULL,
    updated_at  = now();

-- ---------------------------------------------------------------------------
-- 6. Booking settings and time-of-use rates
-- ---------------------------------------------------------------------------
INSERT INTO station_booking_settings (
    id, station_id, min_duration_minutes, duration_step_minutes, max_duration_minutes,
    base_price_vnd, version, created_at, updated_at, created_by, updated_by
)
SELECT
    md5('bkg069-settings-' || s.station_code)::uuid,
    s.id, 30, 30, 180,
    CASE s.station_code WHEN 'ST-1901' THEN 3600.00 WHEN 'ST-1902' THEN 3300.00 ELSE 3100.00 END,
    0, now(), now(), s.owner_id, s.owner_id
FROM stations s
WHERE s.id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid)
ON CONFLICT (station_id) DO UPDATE SET
    min_duration_minutes = EXCLUDED.min_duration_minutes,
    duration_step_minutes = EXCLUDED.duration_step_minutes,
    max_duration_minutes = EXCLUDED.max_duration_minutes,
    base_price_vnd       = EXCLUDED.base_price_vnd,
    updated_at           = now();

INSERT INTO tou_rates (
    id, station_id, name, period_code, day_type, start_time, end_time,
    price_per_kwh, effective_from, effective_to, created_at, updated_at, created_by, updated_by
)
SELECT
    md5('bkg069-tou-' || s.station_code || '-' || r.rate_key)::uuid,
    s.id, r.rate_name, r.period_code, 'DAILY', r.start_time, r.end_time,
    r.price, now() - interval '30 days', NULL, now(), now(), s.owner_id, s.owner_id
FROM stations s
CROSS JOIN LATERAL (VALUES
    ('off-peak', 'Gia thap diem', 'OFF_PEAK', time '00:00', time '06:00', 2800.00::numeric(15,2)),
    ('peak',     'Gia cao diem',  'PEAK',     time '17:00', time '22:00', 4300.00::numeric(15,2))
) AS r(rate_key, rate_name, period_code, start_time, end_time, price)
WHERE s.id IN (md5('bkg069-station-1901')::uuid, md5('bkg069-station-1902')::uuid, md5('bkg069-station-1903')::uuid)
ON CONFLICT (id) DO UPDATE SET
    name           = EXCLUDED.name,
    price_per_kwh  = EXCLUDED.price_per_kwh,
    effective_to   = NULL,
    updated_at     = now();

-- ---------------------------------------------------------------------------
-- 7. Charge points and connectors
--    ST-1901: AVAILABLE, IN_USE and OFFLINE (fault) connectors
--    ST-1902: one healthy connector
--    ST-1903: one healthy connector (host of the ownership-change booking)
-- ---------------------------------------------------------------------------
INSERT INTO charge_points (
    id, station_id, charge_point_code, name, zone_label, max_power_kw,
    provisioning_status, operational_status, version,
    created_at, updated_at, created_by, updated_by, deleted_at
)
VALUES
    (md5('bkg069-cp-1901-01')::uuid, md5('bkg069-station-1901')::uuid, 'CP-B691-01', 'CP B691-01', 'Khu A', 120.00, 'ACTIVE', 'AVAILABLE', 0, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid, NULL),
    (md5('bkg069-cp-1901-02')::uuid, md5('bkg069-station-1901')::uuid, 'CP-B691-02', 'CP B691-02 loi', 'Khu B', 60.00,  'ACTIVE', 'OFFLINE',    0, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid, NULL),
    (md5('bkg069-cp-1902-01')::uuid, md5('bkg069-station-1902')::uuid, 'CP-B692-01', 'CP B692-01', 'Khu A', 60.00,  'ACTIVE', 'AVAILABLE', 0, now(), now(), md5('bkg069-user-owner-b')::uuid, md5('bkg069-user-owner-b')::uuid, NULL),
    (md5('bkg069-cp-1903-01')::uuid, md5('bkg069-station-1903')::uuid, 'CP-B693-01', 'CP B693-01', 'Khu A', 60.00,  'ACTIVE', 'AVAILABLE', 0, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid, NULL)
ON CONFLICT (id) DO UPDATE SET
    name               = EXCLUDED.name,
    zone_label         = EXCLUDED.zone_label,
    max_power_kw       = EXCLUDED.max_power_kw,
    provisioning_status= EXCLUDED.provisioning_status,
    operational_status = EXCLUDED.operational_status,
    deleted_at         = NULL,
    updated_at         = now();

INSERT INTO connectors (
    id, charge_point_id, connector_code, connector_type, power_kw, charger_type,
    runtime_status, version, created_at, updated_at, created_by, updated_by, deleted_at
)
VALUES
    (md5('bkg069-conn-1901-01-1')::uuid, md5('bkg069-cp-1901-01')::uuid, '1', 'TYPE2', 22.00,  'AC', 'AVAILABLE', 0, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid, NULL),
    (md5('bkg069-conn-1901-01-2')::uuid, md5('bkg069-cp-1901-01')::uuid, '2', 'CCS2',  60.00,  'DC', 'IN_USE',    0, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid, NULL),
    (md5('bkg069-conn-1901-01-3')::uuid, md5('bkg069-cp-1901-01')::uuid, '3', 'CCS2',  60.00,  'DC', 'OFFLINE',   0, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid, NULL),
    (md5('bkg069-conn-1902-01-1')::uuid, md5('bkg069-cp-1902-01')::uuid, '1', 'TYPE2', 22.00,  'AC', 'AVAILABLE', 0, now(), now(), md5('bkg069-user-owner-b')::uuid, md5('bkg069-user-owner-b')::uuid, NULL),
    (md5('bkg069-conn-1903-01-1')::uuid, md5('bkg069-cp-1903-01')::uuid, '1', 'CCS2',  60.00,  'DC', 'AVAILABLE', 0, now(), now(), md5('bkg069-user-owner-a')::uuid, md5('bkg069-user-owner-a')::uuid, NULL)
ON CONFLICT (id) DO UPDATE SET
    connector_type = EXCLUDED.connector_type,
    power_kw       = EXCLUDED.power_kw,
    charger_type   = EXCLUDED.charger_type,
    runtime_status = EXCLUDED.runtime_status,
    deleted_at     = NULL,
    updated_at     = now();

-- Equipment status evidence for the fault and busy states above.
INSERT INTO charge_point_status_events (
    id, charge_point_id, status_dimension, from_status, to_status, reason,
    actor_type, performed_by, performed_at
)
VALUES
    (md5('bkg069-cpse-1901-02-fault')::uuid, md5('bkg069-cp-1901-02')::uuid, 'OPERATIONAL',
     'AVAILABLE', 'OFFLINE', 'BKG-069: chan loi mat ket noi vao 2026-10-07',
     'OWNER', md5('bkg069-user-owner-a')::uuid, now() - interval '2 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO connector_status_events (
    id, connector_id, from_status, to_status, reason, actor_type, performed_by, performed_at
)
VALUES
    (md5('bkg069-cose-1901-01-2-inuse')::uuid, md5('bkg069-conn-1901-01-2')::uuid,
     'AVAILABLE', 'IN_USE', 'BKG-069: cong dang trong phien sac', 'OWNER',
     md5('bkg069-user-owner-a')::uuid, now() - interval '1 hour'),
    (md5('bkg069-cose-1901-01-3-fault')::uuid, md5('bkg069-conn-1901-01-3')::uuid,
     'AVAILABLE', 'OFFLINE', 'BKG-069: cong loi khong phuc vu duoc', 'OWNER',
     md5('bkg069-user-owner-a')::uuid, now() - interval '2 days')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8. Staff assignments: one ACTIVE, one REVOKED (twice, incl. at ST-1903)
-- ---------------------------------------------------------------------------
INSERT INTO station_staff_assignments (
    id, station_id, user_id, status, note, assigned_by, assigned_at,
    revoked_by, revoked_at, version
)
VALUES
    (md5('bkg069-staff-1901-active')::uuid, md5('bkg069-station-1901')::uuid,
     md5('bkg069-user-staff-active')::uuid, 'ACTIVE',
     'BKG-069: staff dang hoat dong tai tram 24/7',
     md5('bkg069-user-owner-a')::uuid, now() - interval '20 days', NULL, NULL, 0),

    (md5('bkg069-staff-1901-revoked')::uuid, md5('bkg069-station-1901')::uuid,
     md5('bkg069-user-staff-revoked')::uuid, 'REVOKED',
     'BKG-069: staff bi thu hoi quyen tai tram 24/7',
     md5('bkg069-user-owner-a')::uuid, now() - interval '20 days',
     md5('bkg069-user-owner-a')::uuid, now() - interval '3 days', 1),

    -- Leftover grant from the previous owner of ST-1903; must stay revoked so
    -- the new owner cannot be handed the previous owner's staff.
    (md5('bkg069-staff-1903-revoked')::uuid, md5('bkg069-station-1903')::uuid,
     md5('bkg069-user-staff-revoked')::uuid, 'REVOKED',
     'BKG-069: phan quyen con lai tu chu truoc do cua tram',
     md5('bkg069-user-owner-a')::uuid, now() - interval '25 days',
     md5('bkg069-user-owner-a')::uuid, now() - interval '2 days', 1)
ON CONFLICT (id) DO UPDATE SET
    station_id  = EXCLUDED.station_id,
    user_id     = EXCLUDED.user_id,
    status      = EXCLUDED.status,
    note        = EXCLUDED.note,
    assigned_by = EXCLUDED.assigned_by,
    revoked_by  = EXCLUDED.revoked_by,
    revoked_at  = EXCLUDED.revoked_at,
    version     = EXCLUDED.version;

-- ---------------------------------------------------------------------------
-- 9. Ownership-change booking fixture
--    Written while ST-1903 still belongs to Owner A, so the DB trigger pins
--    bookings.financial_owner_id = Owner A. Ownership then moves to Owner B.
-- ---------------------------------------------------------------------------
INSERT INTO bookings (
    id, driver_id, connector_id, start_at, end_at, status, total_amount,
    station_name_snapshot, station_address_snapshot, charge_point_code_snapshot,
    connector_code_snapshot, expires_at, checked_in_at, completed_at,
    payment_confirmed_at, booking_code, policy_version, version,
    created_at, updated_at, created_by, updated_by
)
VALUES (
    md5('bkg069-booking-own-001')::uuid,
    md5('bkg069-user-driver-a')::uuid,
    md5('bkg069-conn-1903-01-1')::uuid,
    timestamptz '2026-10-01 09:00:00+07',
    timestamptz '2026-10-01 10:00:00+07',
    'COMPLETED',
    120000.00,
    'B69 Tram doi chu so huu',
    '15 Pham Hung, Nam Tu Liem',
    'CP-B693-01',
    '1',
    timestamptz '2026-10-01 08:10:00+07',
    timestamptz '2026-10-01 09:02:00+07',
    timestamptz '2026-10-01 10:00:00+07',
    timestamptz '2026-10-01 08:12:00+07',
    'BKGB69OWN0001',
    'booking-v4.9',
    0,
    timestamptz '2026-10-01 08:05:00+07',
    timestamptz '2026-10-01 10:00:00+07',
    md5('bkg069-user-driver-a')::uuid,
    md5('bkg069-user-driver-a')::uuid
)
ON CONFLICT (id) DO UPDATE SET
    status       = EXCLUDED.status,
    total_amount = EXCLUDED.total_amount,
    updated_at   = now();
-- financial_owner_id is deliberately absent: the trigger owns that column and
-- it must keep the value captured when ST-1903 still belonged to Owner A.

INSERT INTO booking_price_lines (
    id, booking_id, sequence, segment_start, segment_end, duration_minutes,
    label, period_code, rate_vnd_per_kwh, estimated_energy_kwh, power_kw,
    energy_factor, formula_version, amount, created_at, updated_at, created_by, updated_by
)
VALUES (
    md5('bkg069-bpl-own-001-1')::uuid, md5('bkg069-booking-own-001')::uuid, 1,
    timestamptz '2026-10-01 09:00:00+07', timestamptz '2026-10-01 10:00:00+07', 60,
    'BKG-069 fixture', 'BASE', 3100.00, 19.35, 60.00, 1.00,
    'booking-estimate-v1', 120000.00,
    timestamptz '2026-10-01 08:05:00+07', timestamptz '2026-10-01 08:05:00+07',
    md5('bkg069-user-driver-a')::uuid, md5('bkg069-user-driver-a')::uuid
)
ON CONFLICT (id) DO UPDATE SET
    amount   = EXCLUDED.amount,
    updated_at = now();

INSERT INTO payments (
    id, booking_id, amount, status, method, gateway_txn_ref, refund_amount, paid_at,
    provider, receiving_account_ref, currency, version,
    collected_amount, applied_to_package_amount, package_refunded_amount,
    excess_amount, unallocated_amount, needs_reconciliation, payment_code,
    environment, created_at, updated_at, created_by, updated_by
)
VALUES (
    md5('bkg069-payment-own-001')::uuid,
    md5('bkg069-booking-own-001')::uuid,
    120000.00,
    'PAID',
    'SIMULATOR',
    NULL,
    0.00,
    timestamptz '2026-10-01 08:12:00+07',
    'SIMULATOR',
    'SIM-BKG069',
    'VND',
    0,
    120000.00, 120000.00, 0.00, 0.00, 0.00,
    false,
    'PAYBKG069OWN001',
    'SIMULATOR',
    timestamptz '2026-10-01 08:10:00+07',
    timestamptz '2026-10-01 08:12:00+07',
    md5('bkg069-user-driver-a')::uuid,
    md5('bkg069-user-driver-a')::uuid
)
ON CONFLICT (id) DO UPDATE SET
    status         = EXCLUDED.status,
    amount         = EXCLUDED.amount,
    refund_amount  = EXCLUDED.refund_amount,
    updated_at     = now();

INSERT INTO payment_transactions (
    id, payment_id, provider, receiving_account_ref, transaction_ref, amount,
    currency, provider_paid_at, received_at, application_classification,
    application_reason, payment_code, version, created_at, updated_at, created_by, updated_by
)
VALUES (
    md5('bkg069-receipt-own-001')::uuid,
    md5('bkg069-payment-own-001')::uuid,
    'SIMULATOR',
    'SIM-BKG069',
    'SIMBKG069OWN0001',
    120000.00,
    'VND',
    timestamptz '2026-10-01 08:12:00+07',
    timestamptz '2026-10-01 08:12:00+07',
    'APPLIED',
    NULL,
    'PAYBKG069OWN001',
    0,
    timestamptz '2026-10-01 08:12:00+07',
    timestamptz '2026-10-01 08:12:00+07',
    md5('bkg069-user-driver-a')::uuid,
    md5('bkg069-user-driver-a')::uuid
)
ON CONFLICT (id) DO UPDATE SET
    amount      = EXCLUDED.amount,
    updated_at  = now();

-- Ownership moves only after the booking exists, so the booking keeps
-- financial_owner_id = Owner A while the station belongs to Owner B.
UPDATE stations
   SET owner_id    = md5('bkg069-user-owner-b')::uuid,
       updated_by  = md5('bkg069-user-admin')::uuid,
       updated_at  = now()
 WHERE id = md5('bkg069-station-1903')::uuid;

-- The operating license follows the current station owner; only the booking
-- financial snapshot above stays with Owner A.
UPDATE licenses
   SET owner_id   = md5('bkg069-user-owner-b')::uuid,
       updated_by = md5('bkg069-user-admin')::uuid,
       updated_at = now()
 WHERE station_id = md5('bkg069-station-1903')::uuid
   AND status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 10. Escalated support case (no messages/findings: those tables are
--     append-only and cannot be removed by the reset script)
-- ---------------------------------------------------------------------------
INSERT INTO support_tickets (
    id, ticket_code, category, status, priority, reporter_id, assigned_handler_id,
    station_id, booking_id, subject, description, version, created_at, updated_at,
    created_by, updated_by, resolved_at, auto_close_at, close_reason, resolution_cycle
)
VALUES (
    md5('bkg069-ticket-001')::uuid,
    'TKT-20261009-0690',
    'CHARGING_ISSUE',
    'IN_PROGRESS',
    'HIGH',
    md5('bkg069-user-driver-a')::uuid,
    md5('bkg069-user-staff-active')::uuid,
    md5('bkg069-station-1901')::uuid,
    NULL,
    'BKG-069: cong sac loi khong nhan duoc QR',
    'Case demo BKG-069 da duoc nang cap, dung de kiem chung quyen xem va phan quyen giua Owner/Staff/Admin khi dang mo escalation.',
    0,
    now() - interval '2 days',
    now() - interval '1 day',
    md5('bkg069-user-driver-a')::uuid,
    md5('bkg069-user-staff-active')::uuid,
    NULL, NULL, NULL, 0
)
ON CONFLICT (id) DO UPDATE SET
    status       = EXCLUDED.status,
    priority     = EXCLUDED.priority,
    assigned_handler_id = EXCLUDED.assigned_handler_id,
    updated_at   = now();

INSERT INTO ticket_escalations (
    id, ticket_id, requested_by, requested_at, reason,
    resolved_at, resolved_by, resolution_type, resolution_note, closure_reason
)
VALUES (
    md5('bkg069-escalation-001')::uuid,
    md5('bkg069-ticket-001')::uuid,
    md5('bkg069-user-owner-a')::uuid,
    now() - interval '1 day',
    'BKG-069: van de con tiep tuc sau cua so phan hoi cua Owner',
    NULL, NULL, NULL, NULL, NULL
)
ON CONFLICT (id) DO UPDATE SET
    reason = EXCLUDED.reason;

-- ---------------------------------------------------------------------------
-- 11. Keep generated code sequences above every seeded value
-- ---------------------------------------------------------------------------
SELECT setval(
    'station_code_seq',
    GREATEST(
        1903,
        COALESCE((
            SELECT max(substring(station_code FROM '[0-9]+')::bigint)
            FROM stations
            WHERE station_code ~ '^ST-[0-9]+$'
        ), 1903)
    ),
    true
);

SELECT setval(
    'license_code_seq',
    GREATEST(
        206903,
        COALESCE((
            SELECT max(substring(license_code FROM '^LIC-([0-9]+)$')::bigint)
            FROM licenses
            WHERE license_code ~ '^LIC-[0-9]+$'
        ), 206903)
    ),
    true
);

SELECT setval(
    'support_ticket_code_seq',
    GREATEST(
        690,
        COALESCE((
            SELECT max(substring(ticket_code FROM '([0-9]+)$')::bigint)
            FROM support_tickets
            WHERE ticket_code ~ '^TKT-[0-9]{8}-[0-9]+$'
        ), 690)
    ),
    true
);

COMMIT;

-- ---------------------------------------------------------------------------
-- 12. Verification (the seed is complete only when every count matches)
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_users        integer;
    v_stations     integer;
    v_schedules    integer;
    v_periods      integer;
    v_connectors   integer;
    v_staff        integer;
    v_tickets      integer;
    v_escalations  integer;
    v_bookings     integer;
    v_receipts     integer;
    v_owner_mismatch integer;
BEGIN
    SELECT count(*) INTO v_users FROM user_profile WHERE email LIKE 'bkg069.%@example.test';
    SELECT count(*) INTO v_stations FROM stations WHERE station_code IN ('ST-1901','ST-1902','ST-1903');
    SELECT count(*) INTO v_schedules FROM station_operating_schedules
     WHERE id IN (md5('bkg069-sched-1901')::uuid, md5('bkg069-sched-1902')::uuid, md5('bkg069-sched-1903')::uuid);
    SELECT count(*) INTO v_periods FROM station_operating_periods
     WHERE id IN (
        md5('bkg069-period-1902-MONDAY')::uuid,   md5('bkg069-period-1902-TUESDAY')::uuid,
        md5('bkg069-period-1902-WEDNESDAY')::uuid, md5('bkg069-period-1902-THURSDAY')::uuid,
        md5('bkg069-period-1902-FRIDAY')::uuid,    md5('bkg069-period-1902-SATURDAY')::uuid,
        md5('bkg069-period-1902-SUNDAY')::uuid);
    SELECT count(*) INTO v_connectors FROM connectors
     WHERE id IN (
        md5('bkg069-conn-1901-01-1')::uuid, md5('bkg069-conn-1901-01-2')::uuid,
        md5('bkg069-conn-1901-01-3')::uuid, md5('bkg069-conn-1902-01-1')::uuid,
        md5('bkg069-conn-1903-01-1')::uuid);
    SELECT count(*) INTO v_staff FROM station_staff_assignments
     WHERE id IN (md5('bkg069-staff-1901-active')::uuid,
                  md5('bkg069-staff-1901-revoked')::uuid,
                  md5('bkg069-staff-1903-revoked')::uuid);
    SELECT count(*) INTO v_tickets FROM support_tickets WHERE ticket_code = 'TKT-20261009-0690';
    SELECT count(*) INTO v_escalations FROM ticket_escalations WHERE id = md5('bkg069-escalation-001')::uuid;
    SELECT count(*) INTO v_bookings FROM bookings WHERE id = md5('bkg069-booking-own-001')::uuid;
    SELECT count(*) INTO v_receipts FROM payment_transactions
     WHERE id = md5('bkg069-receipt-own-001')::uuid AND application_classification = 'APPLIED';
    SELECT count(*) INTO v_owner_mismatch
      FROM bookings b JOIN stations s ON s.id = (
            SELECT cp.station_id FROM connectors c
              JOIN charge_points cp ON cp.id = c.charge_point_id
             WHERE c.id = b.connector_id)
     WHERE b.id = md5('bkg069-booking-own-001')::uuid
       AND b.financial_owner_id = md5('bkg069-user-owner-a')::uuid
       AND s.owner_id = md5('bkg069-user-owner-b')::uuid;

    IF v_users <> 7 OR v_stations <> 3 OR v_schedules <> 3 OR v_periods <> 7
       OR v_connectors <> 5 OR v_staff <> 3 OR v_tickets <> 1 OR v_escalations <> 1
       OR v_bookings <> 1 OR v_receipts <> 1 OR v_owner_mismatch <> 1 THEN
        RAISE EXCEPTION
            'BKG-069 seed verification failed: users=% stations=% schedules=% periods=% connectors=% staff=% tickets=% escalations=% bookings=% receipts=% ownerMismatch=%',
            v_users, v_stations, v_schedules, v_periods, v_connectors, v_staff,
            v_tickets, v_escalations, v_bookings, v_receipts, v_owner_mismatch;
    END IF;

    RAISE NOTICE 'BKG-069 seed verification OK: 7 users, 3 stations, 5 connectors, 3 staff rows, 1 escalated case, 1 ownership-change booking with 1 APPLIED receipt.';
END $$;

SELECT s.station_code                                   AS station,
       u.email                                          AS owner_email,
       s.status                                         AS status,
       s.operational_status                             AS operational,
       sch.open_24_hours                                AS open_24h,
       (SELECT count(*) FROM connectors c
         JOIN charge_points cp ON cp.id = c.charge_point_id
        WHERE cp.station_id = s.id)                     AS connectors,
       (SELECT count(*) FROM connectors c
         JOIN charge_points cp ON cp.id = c.charge_point_id
        WHERE cp.station_id = s.id AND c.runtime_status <> 'AVAILABLE') AS not_available
FROM stations s
JOIN user_profile u ON u.id = s.owner_id
LEFT JOIN station_operating_schedules sch ON sch.station_id = s.id AND sch.effective_to IS NULL
WHERE s.station_code IN ('ST-1901','ST-1902','ST-1903')
ORDER BY s.station_code;
