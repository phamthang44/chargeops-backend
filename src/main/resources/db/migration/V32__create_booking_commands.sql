-- BKG-011: durable receipts for successful Booking commands.
-- A row is created in the same transaction as the business mutation. Failed
-- transactions therefore leave no command receipt behind.

CREATE TABLE booking_commands (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_profile_id uuid NOT NULL,
    operation varchar(50) NOT NULL,
    request_key uuid NOT NULL,
    payload_hash varchar(64) NOT NULL,
    booking_id uuid NOT NULL,
    created_at timestamptz NOT NULL,

    CONSTRAINT fk_booking_commands_actor
        FOREIGN KEY (actor_profile_id) REFERENCES user_profile(id) ON DELETE RESTRICT,
    CONSTRAINT fk_booking_commands_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT ux_booking_commands_actor_operation_key
        UNIQUE (actor_profile_id, operation, request_key),
    CONSTRAINT ck_booking_commands_operation_not_blank
        CHECK (btrim(operation) <> ''),
    CONSTRAINT ck_booking_commands_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_booking_commands_booking
    ON booking_commands(booking_id);

COMMENT ON TABLE booking_commands IS
    'Successful Booking command receipts used to find the affected Booking on a safe retry.';
