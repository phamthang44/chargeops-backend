-- Legacy ACTIVE stations predate station_operating_schedules. Discovery is
-- intentionally fail-closed when no effective schedule exists, so give those
-- stations an explicit 24/7 schedule instead of persisting a time-derived
-- open_now flag.
WITH active_without_schedule AS (
    SELECT station.id AS station_id,
           MIN(future_schedule.effective_from) AS next_effective_from
    FROM stations station
    LEFT JOIN station_operating_schedules future_schedule
           ON future_schedule.station_id = station.id
          AND future_schedule.effective_from > now()
    WHERE station.status = 'ACTIVE'
      AND station.deleted_at IS NULL
      AND NOT EXISTS (
          SELECT 1
          FROM station_operating_schedules active_schedule
          WHERE active_schedule.station_id = station.id
            AND active_schedule.effective_from <= now()
            AND (
                active_schedule.effective_to IS NULL
                OR active_schedule.effective_to > now()
            )
      )
    GROUP BY station.id
)
INSERT INTO station_operating_schedules (
    id,
    station_id,
    open_24_hours,
    effective_from,
    effective_to,
    created_at,
    updated_at
)
SELECT gen_random_uuid(),
       missing.station_id,
       TRUE,
       now(),
       missing.next_effective_from,
       now(),
       now()
FROM active_without_schedule missing;
