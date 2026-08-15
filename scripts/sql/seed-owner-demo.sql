\set ON_ERROR_STOP on

-- Development-only seed for the owner currently used by the local demo UI.
-- Run after Flyway has applied V1..V6. This is intentionally not a Flyway
-- migration because demo data must never be inserted automatically in other
-- environments.
--
-- Owner: ducthang4342@gmail.com
-- Keycloak subject: 26472179-953a-47fd-bab1-68b3e04ca72a

BEGIN;

DO $$
DECLARE
    v_owner_id uuid;
    v_admin_id uuid;
BEGIN
    SELECT id
    INTO v_owner_id
    FROM user_profile
    WHERE keycloak_id = '26472179-953a-47fd-bab1-68b3e04ca72a'
      AND deleted_at IS NULL;

    IF v_owner_id IS NULL THEN
        RAISE EXCEPTION
            'Owner profile ducthang4342@gmail.com is missing. Log in once so the backend bootstraps user_profile, then run this seed again.';
    END IF;

    -- Use the local admin as actor for approval/rejection events. Falling back
    -- to the owner keeps the seed usable if the admin profile is not bootstrapped.
    SELECT id
    INTO v_admin_id
    FROM user_profile
    WHERE keycloak_id = '10147c7b-ee57-4796-9c23-8af84cd409dc'
      AND deleted_at IS NULL;

    v_admin_id := COALESCE(v_admin_id, v_owner_id);

    IF NOT EXISTS (
        SELECT 1
        FROM wards
        WHERE code IN ('09556', '00166', '00592', '00145')
        HAVING count(*) = 4
    ) THEN
        RAISE EXCEPTION
            'Required Hanoi wards are missing. Confirm Flyway V3 and V4 completed before running this seed.';
    END IF;

    INSERT INTO stations (
        id,
        owner_id,
        station_code,
        name,
        description,
        address_line,
        location,
        ward_code,
        latitude,
        longitude,
        contact_phone,
        planned_charge_point_count,
        status,
        created_at,
        updated_at,
        created_by,
        updated_by,
        deleted_at
    )
    VALUES
        (
            '00000000-0000-4000-8000-000000001001',
            v_owner_id,
            'ST-1001',
            'Trạm Hà Đông',
            'Trạm sạc nhanh tại khu vực Hà Đông, có chỗ nghỉ trong thời gian chờ sạc.',
            '210 Quang Trung, Hà Đông',
            NULL,
            '09556',
            20.971500,
            105.778200,
            '0912345678',
            5,
            'ACTIVE',
            timestamptz '2026-05-12 09:00:00+07',
            timestamptz '2026-05-13 14:30:00+07',
            v_owner_id,
            v_admin_id,
            NULL
        ),
        (
            '00000000-0000-4000-8000-000000001018',
            v_owner_id,
            'ST-1018',
            'Trạm Cầu Giấy',
            'Điểm sạc trung tâm dành cho xe điện tại khu vực Cầu Giấy.',
            '88 Trần Thái Tông, Cầu Giấy',
            NULL,
            '00166',
            21.033700,
            105.785600,
            '0912345678',
            6,
            'ACTIVE',
            timestamptz '2026-06-02 08:15:00+07',
            timestamptz '2026-06-03 10:00:00+07',
            v_owner_id,
            v_admin_id,
            NULL
        ),
        (
            '00000000-0000-4000-8000-000000001056',
            v_owner_id,
            'ST-1056',
            'Trạm Mỹ Đình',
            'Trạm đang chờ quản trị viên xét duyệt.',
            '15 Phạm Hùng, Nam Từ Liêm',
            NULL,
            '00592',
            21.016700,
            105.781000,
            '0912345678',
            4,
            'PENDING_APPROVAL',
            timestamptz '2026-06-26 09:20:00+07',
            timestamptz '2026-06-26 09:20:00+07',
            v_owner_id,
            v_owner_id,
            NULL
        ),
        (
            '00000000-0000-4000-8000-000000001049',
            v_owner_id,
            'ST-1049',
            'Trạm Gia Lâm',
            'Hồ sơ cần được bổ sung trước khi gửi duyệt lại.',
            '7 Ngô Gia Tự, Long Biên',
            NULL,
            '00145',
            21.054200,
            105.894000,
            '0912345678',
            3,
            'REJECTED',
            timestamptz '2026-06-20 13:45:00+07',
            timestamptz '2026-06-21 10:10:00+07',
            v_owner_id,
            v_admin_id,
            NULL
        )
    ON CONFLICT (id) DO UPDATE
    SET owner_id = EXCLUDED.owner_id,
        station_code = EXCLUDED.station_code,
        name = EXCLUDED.name,
        description = EXCLUDED.description,
        address_line = EXCLUDED.address_line,
        location = EXCLUDED.location,
        ward_code = EXCLUDED.ward_code,
        latitude = EXCLUDED.latitude,
        longitude = EXCLUDED.longitude,
        contact_phone = EXCLUDED.contact_phone,
        planned_charge_point_count = EXCLUDED.planned_charge_point_count,
        status = EXCLUDED.status,
        created_at = EXCLUDED.created_at,
        updated_at = EXCLUDED.updated_at,
        created_by = EXCLUDED.created_by,
        updated_by = EXCLUDED.updated_by,
        deleted_at = NULL;

    INSERT INTO station_status_history (
        id,
        station_id,
        event_type,
        from_status,
        to_status,
        reason,
        performed_by,
        performed_at
    )
    VALUES
        (
            '10000000-0000-4000-8000-000000001001',
            '00000000-0000-4000-8000-000000001001',
            'SUBMITTED', NULL, 'PENDING_APPROVAL', NULL,
            v_owner_id,
            timestamptz '2026-05-12 09:00:00+07'
        ),
        (
            '20000000-0000-4000-8000-000000001001',
            '00000000-0000-4000-8000-000000001001',
            'APPROVED', 'PENDING_APPROVAL', 'ACTIVE', NULL,
            v_admin_id,
            timestamptz '2026-05-13 14:30:00+07'
        ),
        (
            '10000000-0000-4000-8000-000000001018',
            '00000000-0000-4000-8000-000000001018',
            'SUBMITTED', NULL, 'PENDING_APPROVAL', NULL,
            v_owner_id,
            timestamptz '2026-06-02 08:15:00+07'
        ),
        (
            '20000000-0000-4000-8000-000000001018',
            '00000000-0000-4000-8000-000000001018',
            'APPROVED', 'PENDING_APPROVAL', 'ACTIVE', NULL,
            v_admin_id,
            timestamptz '2026-06-03 10:00:00+07'
        ),
        (
            '10000000-0000-4000-8000-000000001056',
            '00000000-0000-4000-8000-000000001056',
            'SUBMITTED', NULL, 'PENDING_APPROVAL', NULL,
            v_owner_id,
            timestamptz '2026-06-26 09:20:00+07'
        ),
        (
            '10000000-0000-4000-8000-000000001049',
            '00000000-0000-4000-8000-000000001049',
            'SUBMITTED', NULL, 'PENDING_APPROVAL', NULL,
            v_owner_id,
            timestamptz '2026-06-20 13:45:00+07'
        ),
        (
            '20000000-0000-4000-8000-000000001049',
            '00000000-0000-4000-8000-000000001049',
            'REJECTED', 'PENDING_APPROVAL', 'REJECTED',
            'Thiếu giấy phép kinh doanh hợp lệ; vui lòng nộp lại bản công chứng.',
            v_admin_id,
            timestamptz '2026-06-21 10:10:00+07'
        )
    ON CONFLICT (id) DO UPDATE
    SET station_id = EXCLUDED.station_id,
        event_type = EXCLUDED.event_type,
        from_status = EXCLUDED.from_status,
        to_status = EXCLUDED.to_status,
        reason = EXCLUDED.reason,
        performed_by = EXCLUDED.performed_by,
        performed_at = EXCLUDED.performed_at;

    INSERT INTO licenses (
        id,
        station_id,
        owner_id,
        plan,
        fee_amount,
        start_at,
        expires_at,
        status,
        created_at,
        updated_at,
        created_by,
        updated_by
    )
    VALUES
        (
            '30000000-0000-4000-8000-000000001001',
            '00000000-0000-4000-8000-000000001001',
            v_owner_id,
            'YEARLY',
            3600000.00,
            now() - interval '60 days',
            now() + interval '305 days',
            'ACTIVE',
            now() - interval '60 days',
            now() - interval '60 days',
            v_admin_id,
            v_admin_id
        ),
        (
            '30000000-0000-4000-8000-000000001018',
            '00000000-0000-4000-8000-000000001018',
            v_owner_id,
            'MONTHLY',
            399000.00,
            now() - interval '10 days',
            now() + interval '20 days',
            'ACTIVE',
            now() - interval '10 days',
            now() - interval '10 days',
            v_admin_id,
            v_admin_id
        )
    ON CONFLICT (id) DO UPDATE
    SET station_id = EXCLUDED.station_id,
        owner_id = EXCLUDED.owner_id,
        plan = EXCLUDED.plan,
        fee_amount = EXCLUDED.fee_amount,
        start_at = EXCLUDED.start_at,
        expires_at = EXCLUDED.expires_at,
        status = EXCLUDED.status,
        updated_at = EXCLUDED.updated_at,
        updated_by = EXCLUDED.updated_by;
END $$;

-- Keep generated station codes above the highest demo code. The boolean true
-- means the next nextval() returns max + 1 rather than repeating max.
SELECT setval(
    'station_code_seq',
    GREATEST(
        1056,
        COALESCE((
            SELECT max(substring(station_code FROM '[0-9]+')::bigint)
            FROM stations
            WHERE station_code ~ '^ST-[0-9]+$'
        ), 1056)
    ),
    true
);

COMMIT;

SELECT
    s.station_code,
    s.name,
    p.full_name AS province,
    w.full_name AS ward,
    s.planned_charge_point_count,
    s.status
FROM stations s
JOIN user_profile owner_profile ON owner_profile.id = s.owner_id
JOIN wards w ON w.code = s.ward_code
JOIN provinces p ON p.code = w.province_code
WHERE owner_profile.keycloak_id = '26472179-953a-47fd-bab1-68b3e04ca72a'
  AND s.deleted_at IS NULL
ORDER BY s.created_at;

-- Amenities are intentionally not inserted here: the backend currently has no
-- station amenity table or REST contract. The frontend mock keeps these values:
-- ST-1001 = wifi, coffee, parking, restroom
-- ST-1018 = wifi, food, security
-- Add them through a Flyway migration plus backend API when amenities enter E2.
