ALTER TABLE charge_points
    ADD COLUMN version BIGINT;

UPDATE charge_points
SET version = 0
WHERE version IS NULL;

ALTER TABLE charge_points
    ALTER COLUMN version SET NOT NULL;

ALTER TABLE charge_points
    ALTER COLUMN version SET DEFAULT 0;

ALTER TABLE connectors
    ADD COLUMN version BIGINT;

UPDATE connectors
SET version = 0
WHERE version IS NULL;

ALTER TABLE connectors
    ALTER COLUMN version SET NOT NULL;

ALTER TABLE connectors
    ALTER COLUMN version SET DEFAULT 0;