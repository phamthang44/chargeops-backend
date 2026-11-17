ALTER TABLE station_operating_schedules
    ADD COLUMN min_duration_minutes integer,
    ADD COLUMN base_price_vnd numeric(15,2),
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN cancelled_by_profile_id uuid;

ALTER TABLE station_operating_schedules
    ADD CONSTRAINT ck_station_schedule_min_duration
        CHECK (min_duration_minutes IS NULL OR min_duration_minutes IN (30, 60, 90)),
    ADD CONSTRAINT ck_station_schedule_base_price
        CHECK (base_price_vnd IS NULL OR base_price_vnd > 0),
    ADD CONSTRAINT ck_station_schedule_commercial_values
        CHECK ((min_duration_minutes IS NULL) = (base_price_vnd IS NULL));

UPDATE station_operating_schedules schedule
SET min_duration_minutes = settings.min_duration_minutes,
    base_price_vnd = settings.base_price_vnd
FROM station_booking_settings settings
WHERE settings.station_id = schedule.station_id
  AND schedule.cancelled_at IS NULL
  AND schedule.effective_from <= CURRENT_TIMESTAMP
  AND (schedule.effective_to IS NULL OR schedule.effective_to > CURRENT_TIMESTAMP);

ALTER TABLE tou_rates
    ADD COLUMN schedule_id uuid REFERENCES station_operating_schedules(id) ON DELETE SET NULL;

UPDATE tou_rates rate
SET schedule_id = (
    SELECT schedule.id
    FROM station_operating_schedules schedule
    WHERE schedule.station_id = rate.station_id
      AND schedule.effective_from <= rate.effective_from
      AND (schedule.effective_to IS NULL OR schedule.effective_to >= rate.effective_from)
    ORDER BY schedule.effective_from DESC, schedule.created_at DESC, schedule.id DESC
    LIMIT 1
)
WHERE rate.schedule_id IS NULL;

CREATE INDEX idx_tou_rates_schedule ON tou_rates (schedule_id);

DROP INDEX IF EXISTS uq_station_operating_schedules_current;

CREATE UNIQUE INDEX uq_station_operating_schedules_current
    ON station_operating_schedules (station_id)
    WHERE effective_to IS NULL AND cancelled_at IS NULL;
