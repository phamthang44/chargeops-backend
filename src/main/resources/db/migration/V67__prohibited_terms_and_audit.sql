CREATE TABLE prohibited_terms (
    id uuid PRIMARY KEY,
    term varchar(100) NOT NULL,
    normalized_term varchar(400) NOT NULL CHECK (length(normalized_term) > 0),
    match_type varchar(30) NOT NULL CHECK (match_type IN ('EXACT', 'WORD_OR_PHRASE')),
    enabled boolean NOT NULL DEFAULT false,
    note varchar(500),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid REFERENCES user_profile(id),
    updated_by uuid REFERENCES user_profile(id),
    CONSTRAINT uk_prohibited_term_normalized_match UNIQUE (normalized_term, match_type)
);
CREATE TABLE prohibited_term_scopes (
    term_id uuid NOT NULL REFERENCES prohibited_terms(id) ON DELETE RESTRICT,
    scope varchar(30) NOT NULL CHECK (scope IN ('DISPLAY_NAME', 'STATION_NAME', 'TICKET_SUBJECT', 'TICKET_DESCRIPTION', 'TICKET_MESSAGE')),
    PRIMARY KEY (term_id, scope)
);
CREATE TABLE prohibited_term_changes (
    id uuid PRIMARY KEY,
    term_id uuid NOT NULL REFERENCES prohibited_terms(id) ON DELETE RESTRICT,
    action varchar(20) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'ENABLE', 'DISABLE', 'SEED')),
    actor_id uuid REFERENCES user_profile(id),
    changed_at timestamptz NOT NULL,
    before_snapshot text,
    after_snapshot text NOT NULL
);
CREATE INDEX ix_prohibited_term_changes_history ON prohibited_term_changes(term_id, changed_at DESC, id DESC);
CREATE INDEX ix_prohibited_terms_enabled ON prohibited_terms(enabled) WHERE enabled;

CREATE FUNCTION reject_prohibited_term_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Prohibited term history is append-only';
END;
$$;
CREATE TRIGGER prohibited_term_history_immutable BEFORE UPDATE OR DELETE ON prohibited_term_changes
FOR EACH ROW EXECUTE FUNCTION reject_prohibited_term_history_mutation();

-- A small review list. Nothing is blocked until an administrator enables a rule.
INSERT INTO prohibited_terms(id, term, normalized_term, match_type, enabled, note, created_at, updated_at)
SELECT gen_random_uuid(), term, term, 'WORD_OR_PHRASE', false, 'Starter list: review before enabling', now(), now()
FROM unnest(ARRAY['địt', 'đụ', 'lồn', 'cặc', 'fuck', 'shit', 'asshole', 'bitch']) AS term;
INSERT INTO prohibited_term_scopes(term_id, scope)
SELECT t.id, s.scope FROM prohibited_terms t
CROSS JOIN unnest(ARRAY['DISPLAY_NAME', 'STATION_NAME', 'TICKET_SUBJECT', 'TICKET_DESCRIPTION', 'TICKET_MESSAGE']) AS s(scope);
INSERT INTO prohibited_term_changes(id, term_id, action, changed_at, after_snapshot)
SELECT gen_random_uuid(), t.id, 'SEED', now(), jsonb_build_object(
    'id', t.id, 'term', t.term, 'normalizedTerm', t.normalized_term, 'matchType', t.match_type,
    'scopes', (SELECT jsonb_agg(scope ORDER BY scope) FROM prohibited_term_scopes WHERE term_id=t.id),
    'enabled', t.enabled, 'note', t.note, 'version', t.version,
    'createdAt', t.created_at, 'updatedAt', t.updated_at, 'createdBy', null, 'updatedBy', null)::text
FROM prohibited_terms t;
