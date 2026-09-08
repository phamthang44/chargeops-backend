-- V26: Add keywords array column and index to legal_documents.
-- Editorial baseline keywords are maintained in a separate seed script:
-- scripts/sql/seed-legal-keywords.sql

ALTER TABLE legal_documents
ADD COLUMN IF NOT EXISTS keywords TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_legal_docs_keywords ON legal_documents USING GIN (keywords);
