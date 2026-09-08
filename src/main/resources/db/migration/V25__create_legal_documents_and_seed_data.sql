-- V25: Create the legal document schema. Editorial content is maintained in
-- scripts/sql/seed-legal-documents.sql and is applied manually after Flyway.

CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE IF NOT EXISTS legal_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(100) NOT NULL UNIQUE,
    doc_type VARCHAR(50) NOT NULL,
    target_audience VARCHAR(30) NOT NULL DEFAULT 'ALL',
    title VARCHAR(255) NOT NULL,
    eyebrow VARCHAR(100),
    summary TEXT,
    content TEXT NOT NULL,
    version VARCHAR(30) NOT NULL DEFAULT '1.0.0',
    locale VARCHAR(10) NOT NULL DEFAULT 'vi',
    is_active BOOLEAN NOT NULL DEFAULT true,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_legal_docs_slug ON legal_documents(slug);
CREATE INDEX IF NOT EXISTS idx_legal_docs_lookup ON legal_documents(doc_type, target_audience, is_active, locale);
CREATE INDEX IF NOT EXISTS idx_legal_docs_deleted_at ON legal_documents(deleted_at);
