-- BKG-044/BKG-045: keep lifecycle polling bounded on the two actionable states.

CREATE INDEX idx_bookings_confirmed_check_in_deadline
    ON bookings (check_in_deadline, id)
    WHERE status = 'CONFIRMED';

CREATE INDEX idx_bookings_active_session_end
    ON bookings (end_at, id)
    WHERE status IN ('CHECKED_IN', 'CHARGING');
