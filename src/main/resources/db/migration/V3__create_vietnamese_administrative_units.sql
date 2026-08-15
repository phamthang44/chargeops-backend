-- Core Vietnamese administrative-unit schema adapted from:
-- https://github.com/thanglequoc/vietnamese-provinces-database
-- Pinned dataset release: v4.0.0 (MIT License)

CREATE TABLE administrative_regions (
    id integer PRIMARY KEY,
    name varchar(255) NOT NULL,
    name_en varchar(255) NOT NULL,
    code_name varchar(255),
    code_name_en varchar(255)
);

CREATE TABLE administrative_units (
    id integer PRIMARY KEY,
    full_name varchar(255),
    full_name_en varchar(255),
    short_name varchar(255),
    short_name_en varchar(255),
    code_name varchar(255),
    code_name_en varchar(255)
);

CREATE TABLE provinces (
    code varchar(20) PRIMARY KEY,
    name varchar(255) NOT NULL,
    name_en varchar(255),
    full_name varchar(255) NOT NULL,
    full_name_en varchar(255),
    code_name varchar(255),
    administrative_unit_id integer REFERENCES administrative_units(id)
);

CREATE INDEX idx_provinces_unit ON provinces (administrative_unit_id);

CREATE TABLE wards (
    code varchar(20) PRIMARY KEY,
    name varchar(255) NOT NULL,
    name_en varchar(255),
    full_name varchar(255),
    full_name_en varchar(255),
    code_name varchar(255),
    province_code varchar(20) NOT NULL REFERENCES provinces(code),
    administrative_unit_id integer REFERENCES administrative_units(id)
);

CREATE INDEX idx_wards_province ON wards (province_code);
CREATE INDEX idx_wards_unit ON wards (administrative_unit_id);
