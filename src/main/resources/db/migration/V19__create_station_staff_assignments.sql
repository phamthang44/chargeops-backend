CREATE TABLE station_staff_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL,
    user_id uuid NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    note varchar(500),
    assigned_by uuid NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    revoked_by uuid,
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0,

    CONSTRAINT fk_station_staff_assignments_station
        FOREIGN KEY (station_id) REFERENCES stations(id),
    CONSTRAINT fk_station_staff_assignments_user
        FOREIGN KEY (user_id) REFERENCES user_profile(id),
    CONSTRAINT fk_station_staff_assignments_assigned_by
        FOREIGN KEY (assigned_by) REFERENCES user_profile(id),
    CONSTRAINT fk_station_staff_assignments_revoked_by
        FOREIGN KEY (revoked_by) REFERENCES user_profile(id),
    CONSTRAINT ck_station_staff_assignments_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_station_staff_assignments_lifecycle
        CHECK (
            (status = 'ACTIVE' AND revoked_by IS NULL AND revoked_at IS NULL)
            OR
            (status = 'REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)
        )
);

CREATE INDEX idx_station_staff_assignments_station_status
    ON station_staff_assignments (station_id, status);

CREATE INDEX idx_station_staff_assignments_user
    ON station_staff_assignments (user_id);

CREATE UNIQUE INDEX uq_station_staff_assignments_one_active_per_user
    ON station_staff_assignments (user_id)
    WHERE status = 'ACTIVE';
