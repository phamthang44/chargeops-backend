ALTER TABLE user_profile
    ADD COLUMN primary_role varchar(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN role_synced_at timestamptz;

ALTER TABLE user_profile
    ADD CONSTRAINT ck_user_profile_primary_role
        CHECK (primary_role IN ('DRIVER','OWNER','ADMIN','STAFF','UNKNOWN','CONFLICT'));

CREATE INDEX idx_user_profile_active_primary_role
    ON user_profile(primary_role, id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
