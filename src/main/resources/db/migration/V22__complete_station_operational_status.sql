UPDATE stations
SET operational_status = 'PAUSED',
    operational_status_reason = COALESCE(
        NULLIF(BTRIM(operational_status_reason), ''),
        'Station is not currently active on the platform'
    )
WHERE status <> 'ACTIVE';

ALTER TABLE stations
    ALTER COLUMN operational_status SET DEFAULT 'PAUSED';

ALTER TABLE stations
    ADD CONSTRAINT chk_stations_operational_status
        CHECK (operational_status IN ('OPERATING', 'PAUSED', 'MAINTENANCE'));

CREATE TABLE station_operational_status_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL,
    from_status varchar(30) NOT NULL,
    to_status varchar(30) NOT NULL,
    reason varchar(500),
    performed_by uuid NOT NULL,
    performed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_station_operational_events_station
        FOREIGN KEY (station_id) REFERENCES stations (id),
    CONSTRAINT fk_station_operational_events_performed_by
        FOREIGN KEY (performed_by) REFERENCES user_profile (id),
    CONSTRAINT chk_station_operational_events_statuses
        CHECK (
            from_status IN ('OPERATING', 'PAUSED', 'MAINTENANCE')
            AND to_status IN ('OPERATING', 'PAUSED', 'MAINTENANCE')
        ),
    CONSTRAINT chk_station_operational_events_changed
        CHECK (from_status <> to_status),
    CONSTRAINT chk_station_operational_events_reason
        CHECK (
            to_status = 'OPERATING'
            OR NULLIF(BTRIM(reason), '') IS NOT NULL
        )
);

CREATE INDEX idx_station_operational_events_station_time
    ON station_operational_status_events (station_id, performed_at DESC);

COMMENT ON TABLE station_operational_status_events IS
    'Append-only audit log for owner-controlled station operational transitions.';
