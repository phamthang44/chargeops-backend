-- V11: Enforce Charge Point and Connector Invariants (T17 / BR-CHG)

-- 1. Power range check constraints
ALTER TABLE connectors
    DROP CONSTRAINT IF EXISTS ck_connectors_power_kw;

ALTER TABLE connectors
    ADD CONSTRAINT ck_connectors_power_kw_range
    CHECK (power_kw BETWEEN 3.0 AND 360.0);

ALTER TABLE charge_points
    ADD CONSTRAINT ck_charge_points_max_power_kw_range
    CHECK (
        max_power_kw IS NULL
        OR max_power_kw BETWEEN 3.0 AND 720.0
    );

-- 2. Partial unique indexes with soft delete support
-- Charge Point code uniqueness within station
CREATE UNIQUE INDEX IF NOT EXISTS ux_charge_points_station_code
    ON charge_points (station_id, charge_point_code)
    WHERE deleted_at IS NULL;

-- Connector code uniqueness within Charge Point
CREATE UNIQUE INDEX IF NOT EXISTS ux_connectors_charge_point_code
    ON connectors (charge_point_id, connector_code)
    WHERE deleted_at IS NULL;