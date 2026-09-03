ALTER TABLE stations
    ADD COLUMN operational_status VARCHAR(30) NOT NULL DEFAULT 'OPERATING',
ADD COLUMN operational_status_reason VARCHAR(500);
