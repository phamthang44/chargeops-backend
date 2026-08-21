CREATE TABLE license_status_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    license_id uuid NOT NULL,
    event_type varchar(30) NOT NULL,
    from_status varchar(30),
    to_status varchar(30) NOT NULL,
    reason varchar(500),
    actor_type varchar(20) NOT NULL,
    performed_by uuid,
    performed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_license_status_events_license
        FOREIGN KEY (license_id) REFERENCES licenses (id),
    CONSTRAINT fk_license_status_events_performed_by
        FOREIGN KEY (performed_by) REFERENCES user_profile (id),
    CONSTRAINT chk_license_status_events_event_type
        CHECK (event_type IN (
            'ISSUED',
            'ACTIVATED',
            'SUSPENDED',
            'REACTIVATED',
            'CANCELLED',
            'EXPIRED'
        )),
    CONSTRAINT chk_license_status_events_statuses
        CHECK (
            (from_status IS NULL OR from_status IN (
                'PENDING', 'ACTIVE', 'SUSPENDED', 'CANCELLED', 'EXPIRED'
            ))
            AND to_status IN (
                'PENDING', 'ACTIVE', 'SUSPENDED', 'CANCELLED', 'EXPIRED'
            )
        ),
    CONSTRAINT chk_license_status_events_transition
        CHECK (
            (event_type = 'ISSUED'
                AND from_status IS NULL
                AND to_status = 'PENDING')
            OR (event_type = 'ACTIVATED'
                AND from_status = 'PENDING'
                AND to_status = 'ACTIVE')
            OR (event_type = 'SUSPENDED'
                AND from_status = 'ACTIVE'
                AND to_status = 'SUSPENDED')
            OR (event_type = 'REACTIVATED'
                AND from_status = 'SUSPENDED'
                AND to_status = 'ACTIVE')
            OR (event_type = 'CANCELLED'
                AND from_status IN ('PENDING', 'ACTIVE', 'SUSPENDED')
                AND to_status = 'CANCELLED')
            OR (event_type = 'EXPIRED'
                AND from_status IN ('ACTIVE', 'SUSPENDED')
                AND to_status = 'EXPIRED')
        ),
    CONSTRAINT chk_license_status_events_actor
        CHECK (
            (actor_type = 'USER' AND performed_by IS NOT NULL)
            OR (actor_type = 'SYSTEM' AND performed_by IS NULL)
        )
);

CREATE INDEX idx_license_status_events_license_time
    ON license_status_events (license_id, performed_at DESC);

CREATE INDEX idx_license_status_events_event_time
    ON license_status_events (event_type, performed_at DESC);

COMMENT ON TABLE license_status_events IS
    'Append-only audit events for license lifecycle transitions.';

ALTER TABLE licenses ADD COLUMN version bigint NOT NULL DEFAULT 0;

CREATE SEQUENCE license_code_seq START WITH 1000;

ALTER TABLE licenses
    ADD COLUMN license_code varchar(20);

CREATE UNIQUE INDEX ux_licenses_license_code
    ON licenses (license_code);
