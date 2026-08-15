CREATE TABLE station_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL,
    event_type varchar(30) NOT NULL,
    from_status varchar(30),
    to_status varchar(30) NOT NULL,
    reason varchar(500),
    performed_by uuid NOT NULL,
    performed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_station_status_history_station
        FOREIGN KEY (station_id) REFERENCES stations (id),
    CONSTRAINT fk_station_status_history_performed_by
        FOREIGN KEY (performed_by) REFERENCES user_profile (id),
    CONSTRAINT chk_station_status_history_event_type
        CHECK (event_type IN (
            'SUBMITTED',
            'APPROVED',
            'REJECTED',
            'RESUBMITTED',
            'SUSPENDED',
            'REACTIVATED',
            'WITHDRAWN'
        )),
    CONSTRAINT chk_station_status_history_statuses
        CHECK (
            (from_status IS NULL OR from_status IN (
                'PENDING_APPROVAL', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'WITHDRAWN'
            ))
            AND to_status IN (
                'PENDING_APPROVAL', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'WITHDRAWN'
            )
        ),
    CONSTRAINT chk_station_status_history_transition
        CHECK (
            (event_type = 'SUBMITTED'
                AND from_status IS NULL
                AND to_status = 'PENDING_APPROVAL')
            OR (event_type = 'APPROVED'
                AND from_status = 'PENDING_APPROVAL'
                AND to_status = 'ACTIVE')
            OR (event_type = 'REJECTED'
                AND from_status = 'PENDING_APPROVAL'
                AND to_status = 'REJECTED')
            OR (event_type = 'RESUBMITTED'
                AND from_status = 'REJECTED'
                AND to_status = 'PENDING_APPROVAL')
            OR (event_type = 'SUSPENDED'
                AND from_status = 'ACTIVE'
                AND to_status = 'SUSPENDED')
            OR (event_type = 'REACTIVATED'
                AND from_status = 'SUSPENDED'
                AND to_status = 'ACTIVE')
            OR (event_type = 'WITHDRAWN'
                AND from_status = 'PENDING_APPROVAL'
                AND to_status = 'WITHDRAWN')
        ),
    CONSTRAINT chk_station_status_history_reason
        CHECK (
            event_type NOT IN ('REJECTED', 'SUSPENDED')
            OR NULLIF(btrim(reason), '') IS NOT NULL
        )
);

CREATE INDEX idx_station_status_history_station_time
    ON station_status_history (station_id, performed_at DESC);

CREATE INDEX idx_station_status_history_event_time
    ON station_status_history (event_type, performed_at DESC);

COMMENT ON TABLE station_status_history IS
    'Append-only audit log for station lifecycle and approval transitions.';
