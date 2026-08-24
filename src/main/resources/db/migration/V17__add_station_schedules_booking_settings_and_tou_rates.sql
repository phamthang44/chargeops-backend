-- 1. Create table station_operating_schedules (Planning Header)
CREATE TABLE station_operating_schedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    open_24_hours boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ck_station_operating_schedules_effective CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_station_operating_schedules_station ON station_operating_schedules (station_id, effective_from);

-- 2. Update and migrate station_operating_periods (Planning Detail)
-- Add schedule_id and is_enabled
ALTER TABLE station_operating_periods
    ADD COLUMN schedule_id uuid REFERENCES station_operating_schedules(id) ON DELETE CASCADE,
    ADD COLUMN is_enabled boolean NOT NULL DEFAULT true;

-- For any existing periods (if existing stations exist), create initial schedule and link periods
DO $$
DECLARE
    rec RECORD;
    new_schedule_id uuid;
BEGIN
    FOR rec IN SELECT DISTINCT station_id FROM station_operating_periods WHERE schedule_id IS NULL AND station_id IS NOT NULL LOOP
        INSERT INTO station_operating_schedules (station_id, open_24_hours, effective_from, created_at, updated_at)
        VALUES (rec.station_id, false, now(), now(), now())
        RETURNING id INTO new_schedule_id;

        UPDATE station_operating_periods
        SET schedule_id = new_schedule_id
        WHERE station_id = rec.station_id;
    END LOOP;
END $$;

-- Make open_time and close_time nullable when day is closed (is_enabled = false)
ALTER TABLE station_operating_periods
    ALTER COLUMN open_time DROP NOT NULL,
    ALTER COLUMN close_time DROP NOT NULL;

-- Drop old time constraint and temporal columns from period
ALTER TABLE station_operating_periods
    DROP CONSTRAINT IF EXISTS ck_station_operating_periods_time,
    DROP COLUMN IF EXISTS effective_from,
    DROP COLUMN IF EXISTS effective_to,
    DROP COLUMN IF EXISTS station_id;

-- Make schedule_id NOT NULL after backfill
ALTER TABLE station_operating_periods
    ALTER COLUMN schedule_id SET NOT NULL;

-- Add new domain invariant check constraint
ALTER TABLE station_operating_periods
    ADD CONSTRAINT ck_station_operating_periods_valid_time CHECK (
        (is_enabled = false)
        OR
        (
            open_time IS NOT NULL
            AND close_time IS NOT NULL
            AND close_time > open_time
        )
    );

CREATE INDEX idx_station_operating_periods_schedule_day ON station_operating_periods (schedule_id, day_of_week);

-- 3. Create table station_booking_settings (Station Slot & Duration Settings)
CREATE TABLE station_booking_settings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL UNIQUE REFERENCES stations(id) ON DELETE CASCADE,
    min_duration_minutes integer NOT NULL DEFAULT 30,
    duration_step_minutes integer NOT NULL DEFAULT 30,
    max_duration_minutes integer NOT NULL DEFAULT 180,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ck_station_booking_settings_min_duration CHECK (min_duration_minutes IN (30, 60, 90)),
    CONSTRAINT ck_station_booking_settings_step_duration CHECK (duration_step_minutes > 0),
    CONSTRAINT ck_station_booking_settings_max_duration CHECK (max_duration_minutes >= min_duration_minutes),
    CONSTRAINT ck_station_booking_settings_step_alignment CHECK ((max_duration_minutes - min_duration_minutes) % duration_step_minutes = 0)
);

-- 4. Create table tou_rates (Time-of-Use Dynamic Tariff)
CREATE TABLE tou_rates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    name varchar(100) NOT NULL,
    period_code varchar(20) NOT NULL,
    day_type varchar(20) NOT NULL,
    start_time time NOT NULL,
    end_time time NOT NULL,
    price_per_kwh numeric(15,2) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ck_tou_rates_price CHECK (price_per_kwh >= 0),
    CONSTRAINT ck_tou_rates_effective CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_tou_rates_station_day ON tou_rates (station_id, day_type, effective_from);
CREATE INDEX idx_tou_rates_window ON tou_rates (station_id, day_type, start_time, end_time);
