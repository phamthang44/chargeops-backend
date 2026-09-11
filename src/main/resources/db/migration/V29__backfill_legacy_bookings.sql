-- BKG-008: Defensive backfill for legacy bookings before JPA entity mapping and enum tightening (BKG-009).
-- Handles historical data migrations if any test/legacy rows exist:
-- 1. Migrate legacy status 'NO_SHOW' to 'CANCELLED' with cancellation_reason = 'NO_SHOW' (BR-BOK-05)
-- 2. Bound legacy PENDING reservations by setting expires_at if missing (BR-BOK-02)
-- 3. Calculate check_in_deadline for existing bookings (end_at - 15 minutes) (BR-BOK-04)
-- 4. Generate unique booking_code for existing bookings missing a code
-- 5. Backfill payment_confirmed_at & expired free_cancellation_deadline for already-paid bookings (BR-PAY-02)

-- 1. Standardize NO_SHOW to CANCELLED state with NO_SHOW reason (BR-BOK-05)
UPDATE bookings
SET status = 'CANCELLED',
    cancellation_reason = 'NO_SHOW',
    cancelled_at = COALESCE(cancelled_at, end_at - INTERVAL '15 minutes', updated_at, now())
WHERE status = 'NO_SHOW';

-- 2. Prevent infinite hold on any legacy PENDING bookings missing expires_at (BR-BOK-02)
UPDATE bookings
SET expires_at = created_at + INTERVAL '10 minutes'
WHERE status = 'PENDING' AND expires_at IS NULL;

-- 3. Backfill check_in_deadline (end_at - 15 mins) for rapid indexed querying (BR-BOK-04)
UPDATE bookings
SET check_in_deadline = end_at - INTERVAL '15 minutes'
WHERE check_in_deadline IS NULL;

-- 4. Backfill booking_code for existing rows missing code
UPDATE bookings
SET booking_code = 'BK-LEGACY-' || UPPER(SUBSTRING(REPLACE(id::text, '-', ''), 1, 8))
WHERE booking_code IS NULL;

-- 5. Backfill payment_confirmed_at & free_cancellation_deadline for already-paid bookings
-- Financial safety guard (BR-PAY-02): Do not open a new grace period on legacy rows;
-- free_cancellation_deadline is set to payment_confirmed_at (grace expired in the past).
UPDATE bookings b
SET payment_confirmed_at = COALESCE(p.paid_at, b.created_at),
    free_cancellation_deadline = COALESCE(p.paid_at, b.created_at)
FROM payments p
WHERE b.id = p.booking_id
  AND b.status IN ('CONFIRMED', 'CHECKED_IN', 'CHARGING', 'COMPLETED')
  AND b.payment_confirmed_at IS NULL;
