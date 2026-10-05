CREATE TABLE station_staff_invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES stations(id),
    email varchar(255) NOT NULL,
    identity_user_id varchar(255),
    invited_by uuid NOT NULL REFERENCES user_profile(id),
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    sent_at timestamptz,
    accepted_at timestamptz,
    cancelled_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_station_staff_invitations_status
        CHECK (status IN ('PENDING', 'SENT', 'ACCEPTED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_station_staff_invitations_pending_email
    ON station_staff_invitations (lower(email))
    WHERE status IN ('PENDING', 'SENT');

CREATE UNIQUE INDEX uq_station_staff_invitations_identity_user
    ON station_staff_invitations (identity_user_id)
    WHERE identity_user_id IS NOT NULL;

CREATE INDEX idx_station_staff_invitations_station_created
    ON station_staff_invitations (station_id, created_at DESC);
