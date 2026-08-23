CREATE TABLE charge_point_status_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    charge_point_id uuid NOT NULL,
    status_dimension varchar(20) NOT NULL,
    from_status varchar(30) NOT NULL,
    to_status varchar(30) NOT NULL,
    reason varchar(500),
    actor_type varchar(20) NOT NULL,
    performed_by uuid NOT NULL,
    performed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_cp_status_events_charge_point
        FOREIGN KEY (charge_point_id) REFERENCES charge_points (id),
    CONSTRAINT fk_cp_status_events_performed_by
        FOREIGN KEY (performed_by) REFERENCES user_profile (id),
    CONSTRAINT chk_cp_status_events_actor
        CHECK (actor_type IN ('ADMIN', 'OWNER')),
    CONSTRAINT chk_cp_status_events_dimension_statuses
        CHECK (
            (status_dimension = 'PROVISIONING'
                AND from_status IN ('PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED')
                AND to_status IN ('PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED'))
            OR
            (status_dimension = 'OPERATIONAL'
                AND from_status IN ('AVAILABLE', 'OFFLINE', 'MAINTENANCE')
                AND to_status IN ('AVAILABLE', 'OFFLINE', 'MAINTENANCE'))
        ),
    CONSTRAINT chk_cp_status_events_changed
        CHECK (from_status <> to_status)
);

CREATE INDEX idx_cp_status_events_cp_time
    ON charge_point_status_events (charge_point_id, performed_at);

CREATE INDEX idx_cp_status_events_dimension_time
    ON charge_point_status_events (status_dimension, performed_at);

CREATE TABLE connector_status_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id uuid NOT NULL,
    from_status varchar(30) NOT NULL,
    to_status varchar(30) NOT NULL,
    reason varchar(500),
    actor_type varchar(20) NOT NULL,
    performed_by uuid,
    performed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_connector_status_events_connector
        FOREIGN KEY (connector_id) REFERENCES connectors (id),
    CONSTRAINT fk_connector_status_events_performed_by
        FOREIGN KEY (performed_by) REFERENCES user_profile (id),
    CONSTRAINT chk_connector_status_events_statuses
        CHECK (
            from_status IN ('AVAILABLE', 'IN_USE', 'OFFLINE')
            AND to_status IN ('AVAILABLE', 'IN_USE', 'OFFLINE')
        ),
    CONSTRAINT chk_connector_status_events_actor
        CHECK (
            (actor_type IN ('ADMIN', 'OWNER') AND performed_by IS NOT NULL)
            OR (actor_type = 'SYSTEM' AND performed_by IS NULL)
        ),
    CONSTRAINT chk_connector_status_events_changed
        CHECK (from_status <> to_status)
);

CREATE INDEX idx_connector_status_events_connector_time
    ON connector_status_events (connector_id, performed_at);
