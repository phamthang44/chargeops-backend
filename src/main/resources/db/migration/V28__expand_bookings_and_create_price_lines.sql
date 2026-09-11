-- BKG-007: expand Booking for v4.9 snapshots and create immutable price lines.
-- This migration intentionally keeps new Booking columns nullable until BKG-008
-- has classified and backfilled legacy rows.

ALTER TABLE bookings
    ADD COLUMN booking_code varchar(32),
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN policy_version varchar(100),
    ADD COLUMN policy_snapshot jsonb,
    ADD COLUMN payment_confirmed_at timestamptz,
    ADD COLUMN free_cancellation_deadline timestamptz,
    ADD COLUMN check_in_deadline timestamptz,
    ADD COLUMN cancellation_reason varchar(50),
    ADD COLUMN charging_started_at timestamptz,
    ADD COLUMN completed_at timestamptz;

CREATE UNIQUE INDEX ux_bookings_booking_code
    ON bookings (booking_code)
    WHERE booking_code IS NOT NULL;

CREATE INDEX idx_bookings_driver_start_status
    ON bookings (driver_id, start_at, status);

CREATE TABLE booking_price_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE RESTRICT,
    sequence integer NOT NULL,
    segment_start timestamptz NOT NULL,
    segment_end timestamptz NOT NULL,
    duration_minutes integer NOT NULL,
    label varchar(100) NOT NULL,
    period_code varchar(30) NOT NULL,
    rate_vnd_per_kwh numeric(15,2) NOT NULL,
    estimated_energy_kwh numeric(15,3) NOT NULL,
    power_kw numeric(10,2) NOT NULL,
    energy_factor numeric(8,6) NOT NULL,
    formula_version varchar(50) NOT NULL,
    amount numeric(15,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ux_booking_price_lines_booking_sequence UNIQUE (booking_id, sequence),
    CONSTRAINT ck_booking_price_lines_sequence CHECK (sequence > 0),
    CONSTRAINT ck_booking_price_lines_time_range CHECK (segment_end > segment_start),
    CONSTRAINT ck_booking_price_lines_duration CHECK (duration_minutes > 0),
    CONSTRAINT ck_booking_price_lines_rate CHECK (rate_vnd_per_kwh >= 0),
    CONSTRAINT ck_booking_price_lines_energy CHECK (estimated_energy_kwh >= 0),
    CONSTRAINT ck_booking_price_lines_power CHECK (power_kw > 0),
    CONSTRAINT ck_booking_price_lines_factor CHECK (energy_factor > 0),
    CONSTRAINT ck_booking_price_lines_amount CHECK (amount >= 0)
);

CREATE INDEX idx_booking_price_lines_booking
    ON booking_price_lines (booking_id);
