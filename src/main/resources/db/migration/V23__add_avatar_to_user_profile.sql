ALTER TABLE user_profile
    ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS avatar_storage_key VARCHAR(255);
