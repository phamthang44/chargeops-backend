-- UM-01: admin user management V1 (UM-00 scope cut 10/10/2026)
-- version: optimistic lock cho PATCH status; status_reason/changed_at/changed_by: lần đổi trạng thái gần nhất
ALTER TABLE user_profile
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN status_reason varchar(500),
    ADD COLUMN status_changed_at timestamptz,
    ADD COLUMN status_changed_by uuid;

ALTER TABLE user_profile
    ADD CONSTRAINT fk_user_profile_status_changed_by
        FOREIGN KEY (status_changed_by) REFERENCES user_profile (id);
