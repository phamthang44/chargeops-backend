-- Staff may operate equipment, but may not change charge-point provisioning.
-- Keep the existing constraint names so schema checks and diagnostics stay stable.
ALTER TABLE charge_point_status_events
    DROP CONSTRAINT chk_cp_status_events_actor;

ALTER TABLE charge_point_status_events
    ADD CONSTRAINT chk_cp_status_events_actor
        CHECK (
            actor_type IN ('ADMIN', 'OWNER')
            OR (actor_type = 'STAFF' AND status_dimension = 'OPERATIONAL')
        );

ALTER TABLE connector_status_events
    DROP CONSTRAINT chk_connector_status_events_actor;

ALTER TABLE connector_status_events
    ADD CONSTRAINT chk_connector_status_events_actor
        CHECK (
            (actor_type IN ('ADMIN', 'OWNER', 'STAFF') AND performed_by IS NOT NULL)
            OR (actor_type = 'SYSTEM' AND performed_by IS NULL)
        );
