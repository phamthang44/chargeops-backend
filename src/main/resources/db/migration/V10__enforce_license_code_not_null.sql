-- V8 introduced license_code and its sequence, but existing rows may still be null.
-- Keep the sequence ahead of any manually/backfilled LIC-numeric identifiers.
SELECT setval(
    'license_code_seq',
    GREATEST(
        (SELECT last_value FROM license_code_seq),
        COALESCE((
            SELECT MAX(substring(license_code FROM '^LIC-([0-9]+)$')::bigint)
            FROM licenses
            WHERE license_code ~ '^LIC-[0-9]+$'
        ), 999)
    ),
    true
);

UPDATE licenses
SET license_code = 'LIC-' || lpad(nextval('license_code_seq')::text, 6, '0')
WHERE license_code IS NULL;

ALTER TABLE licenses
    ALTER COLUMN license_code SET NOT NULL;

COMMENT ON SEQUENCE license_code_seq IS
    'Generates the numeric portion of immutable public license codes (LIC-xxxxxx).';
