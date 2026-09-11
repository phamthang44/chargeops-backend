-- BKG-004: system-wide duration policy and pending abuse guard.
INSERT INTO system_configs (id, config_key, config_value, value_type, description, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'booking.duration_min_minutes', '30', 'NUMBER', 'San booking toan he thong (phut)', now(), now()),
    (gen_random_uuid(), 'booking.duration_step_minutes', '30', 'NUMBER', 'Buoc tang thoi luong dat cho (phut)', now(), now()),
    (gen_random_uuid(), 'booking.duration_max_minutes', '180', 'NUMBER', 'Tran thoi luong dat cho toan he thong (phut)', now(), now()),
    (gen_random_uuid(), 'booking.max_pending_per_driver', '1', 'NUMBER', 'So booking PENDING toi da con han tren moi Driver', now(), now())
ON CONFLICT (config_key) DO UPDATE SET description = EXCLUDED.description;
