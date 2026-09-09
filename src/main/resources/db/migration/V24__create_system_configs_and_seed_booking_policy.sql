-- V24: Create system_configs table and seed Booking v4.9 policy defaults (BKG-002)

CREATE TABLE IF NOT EXISTS system_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    value_type VARCHAR(50) NOT NULL DEFAULT 'STRING',
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_system_configs_key ON system_configs(config_key);

-- Seed Booking v4.9 default policies
INSERT INTO system_configs (id, config_key, config_value, value_type, description, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'booking.cancellation_grace_minutes', '10', 'NUMBER', 'Thời gian ân hạn hủy miễn phí tính từ khi xác nhận thanh toán (phút)', now(), now()),
    (gen_random_uuid(), 'booking.payment_hold_minutes', '10', 'NUMBER', 'Thời gian giữ chỗ chờ thanh toán khi tạo booking pending (phút)', now(), now()),
    (gen_random_uuid(), 'booking.minimum_advance_minutes', '60', 'NUMBER', 'Thời gian tối thiểu phải đặt trước giờ bắt đầu sạc (phút)', now(), now()),
    (gen_random_uuid(), 'booking.operating_grid_minutes', '30', 'NUMBER', 'Bước thời gian của mỗi khung giờ đặt chỗ (phút)', now(), now()),
    (gen_random_uuid(), 'booking.advance_booking_days', '2', 'NUMBER', 'Số ngày tối đa được đặt trước (0: hôm nay, 1: ngày mai = 2 ngày)', now(), now()),
    (gen_random_uuid(), 'booking.checkin_cutoff_before_end_minutes', '15', 'NUMBER', 'Thời hạn cuối cùng phải check-in trước giờ kết thúc sạc (phút)', now(), now())
ON CONFLICT (config_key) DO UPDATE SET description = EXCLUDED.description;
