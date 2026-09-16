-- BKG-010: immutable audit trail for Booking lifecycle transitions.

CREATE TABLE booking_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL,
    from_status varchar(30),
    to_status varchar(30) NOT NULL,
    reason varchar(500),
    actor_type varchar(20) NOT NULL,
    actor_profile_id uuid,
    occurred_at timestamptz NOT NULL,
    command_id uuid,

    CONSTRAINT fk_booking_status_history_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT fk_booking_status_history_actor
        FOREIGN KEY (actor_profile_id) REFERENCES user_profile(id) ON DELETE RESTRICT,
    CONSTRAINT fk_booking_status_history_command
        FOREIGN KEY (command_id) REFERENCES booking_commands(id) ON DELETE RESTRICT,
    CONSTRAINT ux_booking_status_history_booking_command
        UNIQUE (booking_id, command_id),
    CONSTRAINT ck_booking_status_history_statuses
        CHECK (
            (from_status IS NULL OR from_status IN (
                'PENDING', 'CONFIRMED', 'CHECKED_IN', 'CHARGING',
                'COMPLETED', 'EXPIRED', 'CANCELLED'
            ))
            AND to_status IN (
                'PENDING', 'CONFIRMED', 'CHECKED_IN', 'CHARGING',
                'COMPLETED', 'EXPIRED', 'CANCELLED'
            )
        ),
    CONSTRAINT ck_booking_status_history_transition
        CHECK (
            (from_status IS NULL AND to_status = 'PENDING')
            OR (from_status = 'PENDING' AND to_status IN ('CONFIRMED', 'CANCELLED', 'EXPIRED'))
            OR (from_status = 'CONFIRMED' AND to_status IN ('CHECKED_IN', 'CANCELLED'))
            OR (from_status = 'CHECKED_IN' AND to_status IN ('CHARGING', 'COMPLETED'))
            OR (from_status = 'CHARGING' AND to_status = 'COMPLETED')
        ),
    CONSTRAINT ck_booking_status_history_actor
        CHECK (
            (actor_type = 'SYSTEM' AND actor_profile_id IS NULL)
            OR (actor_type IN ('DRIVER', 'OWNER', 'ADMIN', 'STAFF') AND actor_profile_id IS NOT NULL)
        )
);

CREATE INDEX idx_booking_status_history_booking_time
    ON booking_status_history(booking_id, occurred_at, id);

CREATE OR REPLACE FUNCTION reject_booking_status_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'booking_status_history is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_booking_status_history_append_only
    BEFORE UPDATE OR DELETE ON booking_status_history
    FOR EACH ROW
    EXECUTE FUNCTION reject_booking_status_history_mutation();

COMMENT ON TABLE booking_status_history IS
    'Append-only audit trail for Booking lifecycle transitions.';
