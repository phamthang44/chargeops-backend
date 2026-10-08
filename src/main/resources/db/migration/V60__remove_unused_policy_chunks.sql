-- BKG-067: Dify owns retrieval chunks; ChargeOps keeps legal_documents and
-- booking policy/config/snapshots. Preserve V1 and its Flyway checksum.
-- Refuse to discard existing content, including inactive/soft-deleted rows.
-- Export/reconcile any content before retrying deployment. No CASCADE.
DO $$
BEGIN
    IF to_regclass('policy_chunks') IS NOT NULL THEN
        LOCK TABLE policy_chunks IN ACCESS EXCLUSIVE MODE;
        IF EXISTS (SELECT 1 FROM policy_chunks) THEN
            RAISE EXCEPTION 'BKG-067: policy_chunks contains data; export and reconcile it before removal';
        END IF;
        DROP TABLE policy_chunks;
    END IF;
END $$;
