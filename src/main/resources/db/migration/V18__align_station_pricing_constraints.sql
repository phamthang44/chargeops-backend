ALTER TABLE station_booking_settings
    ADD COLUMN base_price_vnd numeric(15,2) NOT NULL DEFAULT 3400.00,
    ADD CONSTRAINT ck_station_booking_settings_base_price CHECK (base_price_vnd > 0);

ALTER TABLE station_operating_periods
    DROP CONSTRAINT IF EXISTS ck_station_operating_periods_valid_time;

ALTER TABLE station_operating_periods
    ADD CONSTRAINT ck_station_operating_periods_valid_time CHECK (
        (is_enabled = false AND open_time IS NULL AND close_time IS NULL)
        OR
        (is_enabled = true AND open_time IS NOT NULL AND close_time IS NOT NULL AND open_time <> close_time)
    );

CREATE UNIQUE INDEX uq_station_operating_periods_schedule_day
    ON station_operating_periods (schedule_id, day_of_week)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_station_operating_schedules_current
    ON station_operating_schedules (station_id)
    WHERE effective_to IS NULL;

ALTER TABLE tou_rates
    DROP CONSTRAINT IF EXISTS ck_tou_rates_price;

UPDATE tou_rates
SET day_type = 'WEEKEND'
WHERE day_type IN ('SATURDAY', 'SUNDAY');

ALTER TABLE tou_rates
    ADD CONSTRAINT ck_tou_rates_price CHECK (price_per_kwh > 0),
    ADD CONSTRAINT ck_tou_rates_non_zero_window CHECK (start_time <> end_time),
    ADD CONSTRAINT ck_tou_rates_day_type CHECK (day_type IN ('DAILY', 'WEEKDAY', 'WEEKEND'));
