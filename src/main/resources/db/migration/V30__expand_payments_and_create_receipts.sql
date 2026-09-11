-- BKG-023. Additive migration: amount remains the expected package price.
-- Keep gateway_txn_ref and its legacy unique index until BKG-024 migrates consumers.
-- V1-V29 contain NO receiving account or currency metadata. Never fabricate a
-- receipt (or infer actual collected money from the expected package price).

LOCK TABLE payments IN ACCESS EXCLUSIVE MODE;

CREATE TABLE payment_migration_audit (
    migration_version varchar(20) PRIMARY KEY,
    payment_count_before bigint NOT NULL,
    payment_count_after bigint,
    expected_amount_before numeric NOT NULL,
    expected_amount_after numeric,
    legacy_refund_amount_before numeric NOT NULL,
    legacy_refund_amount_after numeric,
    legacy_rows_requiring_reconciliation bigint,
    recorded_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO payment_migration_audit (
    migration_version, payment_count_before, expected_amount_before, legacy_refund_amount_before
)
SELECT '30', count(*), coalesce(sum(amount), 0), coalesce(sum(refund_amount), 0)
FROM payments;

ALTER TABLE payments
    ADD COLUMN provider varchar(30),
    ADD COLUMN receiving_account_ref varchar(255),
    ADD COLUMN currency varchar(3),
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN collected_amount numeric(19,2),
    ADD COLUMN applied_to_package_amount numeric(19,2),
    ADD COLUMN package_refunded_amount numeric(19,2),
    ADD COLUMN excess_amount numeric(19,2),
    ADD COLUMN unallocated_amount numeric(19,2),
    ADD COLUMN needs_reconciliation boolean NOT NULL DEFAULT true;

-- Unknown projections stay NULL, including for historical PENDING/FAILED rows:
-- their status alone is not evidence that no money arrived.
-- New writes in BKG-024 must explicitly initialize known amounts and metadata.
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_currency CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_payments_provider CHECK (provider IS NULL OR btrim(provider) <> ''),
    ADD CONSTRAINT ck_payments_account CHECK (receiving_account_ref IS NULL OR btrim(receiving_account_ref) <> ''),
    ADD CONSTRAINT ck_payments_projection_amounts CHECK (
        collected_amount >= 0 AND collected_amount <> 'NaN'::numeric
        AND applied_to_package_amount >= 0 AND applied_to_package_amount <> 'NaN'::numeric
        AND package_refunded_amount >= 0 AND package_refunded_amount <> 'NaN'::numeric
        AND excess_amount >= 0 AND excess_amount <> 'NaN'::numeric
        AND unallocated_amount >= 0 AND unallocated_amount <> 'NaN'::numeric
    ),
    ADD CONSTRAINT ck_payments_projection_bounds CHECK (
        applied_to_package_amount <= amount
        AND package_refunded_amount <= applied_to_package_amount
        AND applied_to_package_amount + excess_amount + unallocated_amount = collected_amount
    ),
    ADD CONSTRAINT ck_payments_reconciled_metadata CHECK (
        needs_reconciliation OR (
            provider IS NOT NULL AND receiving_account_ref IS NOT NULL AND currency IS NOT NULL
            AND collected_amount IS NOT NULL AND applied_to_package_amount IS NOT NULL
            AND package_refunded_amount IS NOT NULL AND excess_amount IS NOT NULL
            AND unallocated_amount IS NOT NULL
        )
    );

CREATE TABLE payment_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid REFERENCES payments(id) ON DELETE RESTRICT,
    provider varchar(30) NOT NULL,
    receiving_account_ref varchar(255) NOT NULL,
    transaction_ref varchar(255) NOT NULL,
    amount numeric(19,2) NOT NULL,
    currency varchar(3) NOT NULL,
    provider_paid_at timestamptz,
    received_at timestamptz NOT NULL DEFAULT now(),
    application_classification varchar(30) NOT NULL,
    payment_code varchar(50),
    transfer_content text,
    raw_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT ux_payment_transactions_provider_account_ref
        UNIQUE (provider, receiving_account_ref, transaction_ref),
    CONSTRAINT ck_payment_transactions_identity CHECK (
        btrim(provider) <> '' AND btrim(receiving_account_ref) <> '' AND btrim(transaction_ref) <> ''
    ),
    CONSTRAINT ck_payment_transactions_amount CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    CONSTRAINT ck_payment_transactions_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_transactions_classification CHECK (
        application_classification IN ('UNMATCHED', 'UNAPPLIED', 'APPLIED', 'LATE', 'UNDERPAID', 'OVERPAID', 'EXCESS')
    ),
    CONSTRAINT ck_payment_transactions_applied_payment CHECK (
        application_classification <> 'APPLIED' OR payment_id IS NOT NULL
    )
);

CREATE INDEX idx_payment_transactions_payment ON payment_transactions(payment_id);
CREATE INDEX idx_payment_transactions_classification_received
    ON payment_transactions(application_classification, received_at);

COMMENT ON TABLE payment_transactions IS 'Incoming money receipts only; outgoing refunds have a separate lifecycle.';
COMMENT ON COLUMN payments.amount IS 'Expected package price, not actual collected money.';
COMMENT ON COLUMN payments.collected_amount IS 'Gross collected money; NULL means unknown legacy value, not zero.';
COMMENT ON COLUMN payments.excess_amount IS 'Gross identified excess receipts, not the remaining refundable balance.';
COMMENT ON COLUMN payments.needs_reconciliation IS 'Legacy rows lack receiving account/currency and receipt evidence; preserve gateway_txn_ref and refund_amount for reconciliation.';

UPDATE payment_migration_audit
SET payment_count_after = (SELECT count(*) FROM payments),
    expected_amount_after = (SELECT coalesce(sum(amount), 0) FROM payments),
    legacy_refund_amount_after = (SELECT coalesce(sum(refund_amount), 0) FROM payments),
    legacy_rows_requiring_reconciliation = (SELECT count(*) FROM payments WHERE needs_reconciliation)
WHERE migration_version = '30';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM payment_migration_audit WHERE migration_version = '30'
        AND (payment_count_before <> payment_count_after
            OR expected_amount_before IS DISTINCT FROM expected_amount_after
            OR legacy_refund_amount_before IS DISTINCT FROM legacy_refund_amount_after)
    ) THEN
        RAISE EXCEPTION 'BKG-023: legacy payment totals changed; aborting migration';
    END IF;
END $$;
