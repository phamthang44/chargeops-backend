ALTER TABLE payments
    ADD COLUMN environment varchar(10);

-- ChargeOps currently integrates SePay through its sandbox-only adapter.
-- Existing SIMULATOR and SEPAY rows therefore represent non-monetary test data.
UPDATE payments
SET environment = CASE
    WHEN method = 'SIMULATOR' OR provider = 'SEPAY' THEN 'TEST'
    ELSE 'LEGACY'
END;

ALTER TABLE payments
    ALTER COLUMN environment SET NOT NULL,
    ADD CONSTRAINT ck_payments_environment
        CHECK (environment IN ('TEST', 'LEGACY', 'LIVE'));

CREATE INDEX idx_payments_environment_status
    ON payments(environment, status);

COMMENT ON COLUMN payments.environment IS
    'Financial data boundary. Owner revenue, payout and accounting queries must include LIVE only.';
