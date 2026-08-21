ALTER TABLE licenses
    ADD COLUMN renewed_from_license_id uuid;

ALTER TABLE licenses
    ADD CONSTRAINT fk_licenses_renewed_from
        FOREIGN KEY (renewed_from_license_id) REFERENCES licenses (id),
    ADD CONSTRAINT ck_licenses_not_self_renewal
        CHECK (
            renewed_from_license_id IS NULL
            OR renewed_from_license_id <> id
        );

CREATE UNIQUE INDEX ux_licenses_renewed_from
    ON licenses (renewed_from_license_id)
    WHERE renewed_from_license_id IS NOT NULL;

COMMENT ON COLUMN licenses.renewed_from_license_id IS
    'Source license for a renewal. Unique so one source can create at most one direct successor.';
