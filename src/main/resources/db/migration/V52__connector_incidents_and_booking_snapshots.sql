-- BKG-054/055: operational evidence, independent of financial authority.
CREATE TABLE connector_incidents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id uuid NOT NULL REFERENCES connectors(id) ON DELETE RESTRICT,
    status varchar(20) NOT NULL CHECK (status IN ('OPEN', 'RECOVERED')),
    reason varchar(2000) NOT NULL CHECK (length(trim(reason)) BETWEEN 5 AND 2000),
    occurred_at timestamptz NOT NULL,
    reported_at timestamptz NOT NULL CHECK (occurred_at <= reported_at),
    reported_by uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    actor_type varchar(20) NOT NULL CHECK (actor_type IN ('OWNER', 'STAFF', 'ADMIN')),
    previous_runtime_status varchar(30) NOT NULL CHECK (previous_runtime_status IN ('AVAILABLE', 'IN_USE', 'OFFLINE')),
    recovered_at timestamptz,
    recovered_by uuid REFERENCES user_profile(id) ON DELETE RESTRICT,
    recovery_actor_type varchar(20) CHECK (recovery_actor_type IN ('OWNER', 'STAFF', 'ADMIN')),
    recovery_reason varchar(2000),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_incident_recovery CHECK (
        (status = 'OPEN' AND recovered_at IS NULL AND recovered_by IS NULL
            AND recovery_reason IS NULL AND recovery_actor_type IS NULL)
        OR (status = 'RECOVERED' AND recovered_at >= reported_at AND recovered_by IS NOT NULL
            AND recovery_actor_type IS NOT NULL AND length(trim(recovery_reason)) BETWEEN 5 AND 2000))
);
CREATE INDEX idx_incidents_connector_open ON connector_incidents(connector_id) WHERE status = 'OPEN';
CREATE TABLE incident_bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id uuid NOT NULL REFERENCES connector_incidents(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE RESTRICT,
    snapshot_status varchar(30) NOT NULL CHECK (snapshot_status IN ('PENDING','CONFIRMED','CHECKED_IN','CHARGING')),
    snapshot_version bigint NOT NULL CHECK (snapshot_version >= 0),
    state varchar(30) NOT NULL CHECK (state IN ('AWAITING_SESSION_STOP','NO_ACTIVE_SESSION','SESSION_RESOLVED')),
    resolved_at timestamptz,
    resolved_by uuid REFERENCES user_profile(id) ON DELETE RESTRICT,
    resolution_reason varchar(2000),
    UNIQUE (incident_id, booking_id),
    CONSTRAINT ck_incident_booking_resolution CHECK (
        (state = 'SESSION_RESOLVED' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL
            AND length(trim(resolution_reason)) BETWEEN 5 AND 2000)
        OR (state <> 'SESSION_RESOLVED' AND resolved_at IS NULL AND resolved_by IS NULL AND resolution_reason IS NULL))
);
CREATE INDEX idx_incident_bookings_booking ON incident_bookings(booking_id);
CREATE TABLE incident_commands (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL REFERENCES user_profile(id) ON DELETE RESTRICT,
    incident_id uuid NOT NULL REFERENCES connector_incidents(id) ON DELETE RESTRICT,
    operation varchar(30) NOT NULL CHECK (operation IN ('REPORT','RECOVER','RESOLVE_SESSION')),
    request_key uuid NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    UNIQUE (actor_id, operation, request_key)
);

CREATE FUNCTION guard_incident_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Incident evidence cannot be deleted'; END IF;
    IF TG_TABLE_NAME = 'incident_commands' THEN RAISE EXCEPTION 'Incident commands are append-only'; END IF;
    IF TG_TABLE_NAME = 'connector_incidents' THEN
        IF OLD.status <> 'OPEN' OR NEW.status <> 'RECOVERED'
            OR (NEW.id,NEW.connector_id,NEW.reason,NEW.occurred_at,NEW.reported_at,NEW.reported_by,NEW.actor_type,NEW.previous_runtime_status)
            IS DISTINCT FROM (OLD.id,OLD.connector_id,OLD.reason,OLD.occurred_at,OLD.reported_at,OLD.reported_by,OLD.actor_type,OLD.previous_runtime_status)
        THEN RAISE EXCEPTION 'Incident evidence is immutable except one recovery'; END IF;
    ELSIF TG_TABLE_NAME = 'incident_bookings' THEN
        IF OLD.state <> 'AWAITING_SESSION_STOP' OR NEW.state <> 'SESSION_RESOLVED'
            OR (NEW.id,NEW.incident_id,NEW.booking_id,NEW.snapshot_status,NEW.snapshot_version)
            IS DISTINCT FROM (OLD.id,OLD.incident_id,OLD.booking_id,OLD.snapshot_status,OLD.snapshot_version)
        THEN RAISE EXCEPTION 'Incident snapshot is immutable except one resolution'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_incident_evidence BEFORE UPDATE OR DELETE ON connector_incidents
    FOR EACH ROW EXECUTE FUNCTION guard_incident_evidence();
CREATE TRIGGER trg_incident_booking_evidence BEFORE UPDATE OR DELETE ON incident_bookings
    FOR EACH ROW EXECUTE FUNCTION guard_incident_evidence();
CREATE TRIGGER trg_incident_command_evidence BEFORE UPDATE OR DELETE ON incident_commands
    FOR EACH ROW EXECUTE FUNCTION guard_incident_evidence();
