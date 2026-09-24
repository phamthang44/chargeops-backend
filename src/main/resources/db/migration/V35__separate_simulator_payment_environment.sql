-- Separate non-monetary offline SIMULATOR data from provider sandbox TEST data.
ALTER TABLE payments
    DROP CONSTRAINT ck_payments_environment;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_environment
        CHECK (environment IN ('SIMULATOR', 'TEST', 'LEGACY', 'LIVE'));

UPDATE payments
SET environment = 'SIMULATOR'
WHERE method = 'SIMULATOR';

COMMENT ON COLUMN payments.environment IS
    'Financial boundary: SIMULATOR, TEST, LIVE or unclassified LEGACY. Queries must select one environment and exclude LEGACY.';
