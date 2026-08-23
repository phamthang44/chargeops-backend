ALTER TABLE charge_points
    ADD COLUMN operational_status VARCHAR(30);

UPDATE charge_points
SET operational_status = 'AVAILABLE'
WHERE operational_status IS NULL;

ALTER TABLE charge_points
    ALTER COLUMN operational_status SET NOT NULL;