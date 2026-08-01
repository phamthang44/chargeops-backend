CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE user_profile (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id varchar(255) NOT NULL UNIQUE,
    email varchar(320) NOT NULL UNIQUE,
    display_name varchar(255),
    phone varchar(20) NOT NULL,
    status varchar(30),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted_at timestamptz
);

CREATE INDEX idx_user_profile_email ON user_profile (email);

CREATE TABLE stations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES user_profile(id),
    name varchar(100) NOT NULL,
    description varchar(500) NOT NULL,
    address varchar(200) NOT NULL,
    location varchar(100) NOT NULL,
    contact_phone varchar(20) NOT NULL,
    status varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted_at timestamptz
);

CREATE INDEX idx_stations_owner_id ON stations (owner_id);
CREATE INDEX idx_stations_status ON stations (status);

CREATE TABLE station_assets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES stations(id),
    asset_type varchar(30) NOT NULL,
    asset_url varchar(500) NOT NULL,
    storage_key varchar(255),
    alt_text varchar(255),
    display_order integer NOT NULL DEFAULT 0,
    is_primary boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted_at timestamptz
);

CREATE INDEX idx_station_assets_station_id ON station_assets (station_id);
CREATE INDEX idx_station_assets_station_order ON station_assets (station_id, display_order);
CREATE UNIQUE INDEX ux_station_assets_one_primary
    ON station_assets (station_id)
    WHERE is_primary = true AND deleted_at IS NULL;

CREATE TABLE station_operating_periods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES stations(id),
    day_of_week varchar(20) NOT NULL,
    open_time time NOT NULL,
    close_time time NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted_at timestamptz,
    CONSTRAINT ck_station_operating_periods_time CHECK (close_time > open_time)
);

CREATE INDEX idx_station_operating_periods_station_id ON station_operating_periods (station_id);
CREATE INDEX idx_station_operating_periods_station_day
    ON station_operating_periods (station_id, day_of_week, effective_from);

CREATE TABLE charge_points (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES stations(id),
    charge_point_code varchar(80) NOT NULL,
    name varchar(100),
    zone_label varchar(100),
    max_power_kw numeric(8,2),
    provisioning_status varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted_at timestamptz
);

CREATE INDEX idx_charge_points_station_id ON charge_points (station_id);

CREATE TABLE connectors (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    charge_point_id uuid NOT NULL REFERENCES charge_points(id),
    connector_code varchar(50) NOT NULL,
    qr_token uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    connector_type varchar(30) NOT NULL,
    power_kw numeric(8,2) NOT NULL,
    charger_type varchar(10) NOT NULL,
    slot_minutes integer NOT NULL DEFAULT 30,
    runtime_status varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted_at timestamptz,
    CONSTRAINT ck_connectors_power_kw CHECK (power_kw > 0),
    CONSTRAINT ck_connectors_slot_minutes CHECK (slot_minutes > 0)
);

CREATE INDEX idx_connectors_charge_point_id ON connectors (charge_point_id);

CREATE TABLE licenses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES stations(id),
    owner_id uuid NOT NULL REFERENCES user_profile(id),
    plan varchar(30) NOT NULL,
    fee_amount numeric(19,2) NOT NULL,
    start_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ck_licenses_dates CHECK (expires_at > start_at),
    CONSTRAINT ck_licenses_fee CHECK (fee_amount >= 0)
);

CREATE INDEX idx_licenses_station_id ON licenses (station_id);
CREATE INDEX idx_licenses_owner_id ON licenses (owner_id);
CREATE UNIQUE INDEX ux_licenses_one_active_per_station
    ON licenses (station_id)
    WHERE status = 'ACTIVE';

CREATE TABLE bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id uuid NOT NULL REFERENCES user_profile(id),
    connector_id uuid NOT NULL REFERENCES connectors(id),
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    status varchar(30) NOT NULL,
    total_amount numeric(15,2) NOT NULL,
    station_name_snapshot varchar(255) NOT NULL,
    station_address_snapshot varchar(255) NOT NULL,
    charge_point_code_snapshot varchar(255) NOT NULL,
    connector_code_snapshot varchar(255) NOT NULL,
    expires_at timestamptz,
    checked_in_at timestamptz,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ck_bookings_time_range CHECK (end_at > start_at),
    CONSTRAINT ck_bookings_total_amount CHECK (total_amount >= 0)
);

CREATE INDEX idx_bookings_driver_id ON bookings (driver_id);
CREATE INDEX idx_bookings_connector_id ON bookings (connector_id);
CREATE INDEX idx_bookings_status ON bookings (status);
CREATE INDEX idx_bookings_connector_range ON bookings (connector_id, start_at, end_at);

ALTER TABLE bookings
    ADD CONSTRAINT ex_bookings_no_overlap
    EXCLUDE USING gist (
        connector_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    )
    WHERE (status IN ('CONFIRMED', 'CHECKED_IN', 'CHARGING'));

CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL UNIQUE REFERENCES bookings(id),
    amount numeric(15,2) NOT NULL,
    status varchar(30) NOT NULL,
    method varchar(30),
    gateway_txn_ref varchar(255),
    refund_amount numeric(15,2),
    paid_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ck_payments_amount CHECK (amount >= 0),
    CONSTRAINT ck_payments_refund_amount CHECK (refund_amount IS NULL OR refund_amount >= 0)
);

CREATE INDEX idx_payments_status ON payments (status);
CREATE UNIQUE INDEX ux_payments_gateway_txn_ref
    ON payments (gateway_txn_ref)
    WHERE gateway_txn_ref IS NOT NULL;

CREATE TABLE policy_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title varchar(255) NOT NULL,
    content text NOT NULL,
    category varchar(50),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    deleted_at timestamptz
);
