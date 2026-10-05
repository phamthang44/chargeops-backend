-- BKG-034/035: retain the operator note and the actual execution time.
-- STARTED remains valid for future network providers, while core Simulator and
-- Manual Record attempts are inserted and completed in one local transaction.

ALTER TABLE refund_attempts
    ADD COLUMN performed_at timestamptz,
    ADD COLUMN note varchar(2000);

UPDATE refund_attempts
   SET performed_at = COALESCE(completed_at, started_at),
       note = COALESCE(note, 'Legacy refund attempt')
 WHERE status IN ('SUCCEEDED', 'FAILED');

ALTER TABLE refund_attempts
    DROP CONSTRAINT ck_refund_attempts_terminal_state;

ALTER TABLE refund_attempts
    ADD CONSTRAINT ck_refund_attempts_terminal_state CHECK (
        (status = 'STARTED' AND completed_at IS NULL
            AND performed_at IS NULL AND note IS NULL
            AND transfer_reference IS NULL AND failure_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL
            AND completed_at >= started_at
            AND performed_at IS NOT NULL
            AND NULLIF(btrim(note), '') IS NOT NULL
            AND NULLIF(btrim(transfer_reference), '') IS NOT NULL
            AND failure_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL
            AND completed_at >= started_at
            AND performed_at IS NOT NULL
            AND NULLIF(btrim(note), '') IS NOT NULL
            AND transfer_reference IS NULL
            AND NULLIF(btrim(failure_code), '') IS NOT NULL)
    );

ALTER TABLE refund_attempts
    ADD CONSTRAINT ck_refund_attempts_note_length
        CHECK (note IS NULL OR char_length(note) <= 2000);

