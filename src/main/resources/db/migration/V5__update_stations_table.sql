CREATE SEQUENCE station_code_seq START WITH 1000;

ALTER TABLE stations
    RENAME COLUMN address TO address_line;

ALTER TABLE stations
    ADD COLUMN station_code varchar(20),
    ADD COLUMN ward_code varchar(20) REFERENCES wards(code),
    ADD COLUMN latitude numeric(9, 6),
    ADD COLUMN longitude numeric(10, 6),
    ADD COLUMN planned_charge_point_count integer NOT NULL DEFAULT 1;

UPDATE stations
SET station_code = 'ST-' || lpad(nextval('station_code_seq')::text, 4, '0');

ALTER TABLE stations
    ALTER COLUMN station_code SET NOT NULL,
    ALTER COLUMN station_code SET DEFAULT ('ST-' || lpad(nextval('station_code_seq')::text, 4, '0')),
    ALTER COLUMN description DROP NOT NULL,
    ALTER COLUMN location DROP NOT NULL,
    ADD CONSTRAINT ux_stations_station_code UNIQUE (station_code),
    ADD CONSTRAINT chk_stations_latitude
        CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT chk_stations_longitude
        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    ADD CONSTRAINT chk_stations_coordinates_pair
        CHECK ((latitude IS NULL) = (longitude IS NULL)),
    ADD CONSTRAINT chk_stations_planned_charge_point_count
        CHECK (planned_charge_point_count > 0);

CREATE INDEX idx_stations_ward_code ON stations (ward_code);

COMMENT ON COLUMN stations.location IS
    'Legacy free-text location. New station registrations use ward_code.';
