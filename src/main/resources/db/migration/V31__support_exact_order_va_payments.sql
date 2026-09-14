-- Preserve V30 and historical money; new writes use Order VA, not allocation buckets.
ALTER TABLE payments
    ADD COLUMN payment_code varchar(50),
    ADD COLUMN provider_order_ref varchar(255),
    ADD COLUMN va_number varchar(255),
    ADD COLUMN provider_expires_at timestamptz,
    ADD COLUMN qr_code text,
    ADD COLUMN qr_code_url text;
CREATE UNIQUE INDEX ux_payments_payment_code ON payments(payment_code) WHERE payment_code IS NOT NULL;
CREATE UNIQUE INDEX ux_payments_provider_order ON payments(provider, receiving_account_ref, provider_order_ref)
    WHERE provider_order_ref IS NOT NULL;
ALTER TABLE payments DROP CONSTRAINT ck_payments_reconciled_metadata;
ALTER TABLE payments ADD CONSTRAINT ck_payments_reconciled_metadata CHECK (
    needs_reconciliation OR (
        provider IS NOT NULL AND receiving_account_ref IS NOT NULL AND currency IS NOT NULL
        AND (payment_code IS NOT NULL OR (
            collected_amount IS NOT NULL AND applied_to_package_amount IS NOT NULL
            AND package_refunded_amount IS NOT NULL AND excess_amount IS NOT NULL AND unallocated_amount IS NOT NULL
        ))
    )
);
ALTER TABLE payments ADD CONSTRAINT ck_payments_order_va CHECK (
    payment_code IS NULL OR (
        payment_code ~ '^[A-Z0-9]{6,50}$'
        AND provider IS NOT NULL AND receiving_account_ref IS NOT NULL
        AND currency IS NOT NULL AND currency = 'VND'
        AND method IS NOT NULL AND method IN ('BANK_TRANSFER', 'SIMULATOR')
        AND amount > 0 AND amount <= 999999999999 AND amount = trunc(amount)
        AND refund_amount IS NOT NULL AND refund_amount IN (0, amount)
    )
);
ALTER TABLE payments ADD CONSTRAINT ck_payments_order_binding CHECK (
    (provider_order_ref IS NULL AND va_number IS NULL AND provider_expires_at IS NULL)
    OR (payment_code IS NOT NULL AND provider_order_ref IS NOT NULL AND btrim(provider_order_ref) <> ''
        AND va_number IS NOT NULL AND btrim(va_number) <> '' AND provider_expires_at IS NOT NULL)
);
ALTER TABLE payment_transactions
    ADD COLUMN application_reason text,
    ADD COLUMN va_number varchar(255),
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
UPDATE payment_transactions SET application_reason = application_classification
WHERE application_classification <> 'APPLIED';
ALTER TABLE payment_transactions DROP CONSTRAINT ck_payment_transactions_classification;
UPDATE payment_transactions SET application_classification = 'UNAPPLIED'
WHERE application_classification <> 'APPLIED';
ALTER TABLE payment_transactions ADD CONSTRAINT ck_payment_transactions_classification
    CHECK (application_classification IN ('UNAPPLIED', 'APPLIED'));
ALTER TABLE payment_transactions ADD CONSTRAINT ck_payment_transactions_application_reason CHECK (
    (application_classification = 'APPLIED' AND application_reason IS NULL)
    OR (application_classification = 'UNAPPLIED' AND application_reason IS NOT NULL AND btrim(application_reason) <> '')
);
-- New Order receipts can cover a payment at most once; legacy receipts have no VA.
CREATE UNIQUE INDEX ux_payment_transactions_one_applied_order
    ON payment_transactions(payment_id)
    WHERE application_classification = 'APPLIED' AND va_number IS NOT NULL;
COMMENT ON COLUMN payments.payment_code IS 'Server-generated Order code, persisted before provider call. NULL identifies legacy payment.';
COMMENT ON COLUMN payments.receiving_account_ref IS 'Stable merchant account identity, not the dynamic VA number.';
COMMENT ON COLUMN payments.collected_amount IS 'Legacy V30 only. New Order VA writes leave NULL; derive totals from receipts.';
COMMENT ON COLUMN payments.applied_to_package_amount IS 'Legacy V30 only; new payments accept an entire exact receipt.';
COMMENT ON COLUMN payments.package_refunded_amount IS 'Legacy V30 only; new full refund total uses refund_amount.';
COMMENT ON COLUMN payments.excess_amount IS 'Legacy V30 only; preserved, no new bucket allocation.';
COMMENT ON COLUMN payments.unallocated_amount IS 'Legacy V30 only; new unapplied money remains on receipt.';
