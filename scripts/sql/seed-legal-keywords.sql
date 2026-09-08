-- ChargeOps: baseline keyword catalog for 15 legal documents.
-- Apply Flyway V26 first (ensures keywords TEXT[] exists).
-- Run independently:
-- psql -X -v ON_ERROR_STOP=1 -d chargeops -f scripts/sql/seed-legal-keywords.sql
--
-- Policy: Merges seed keywords with existing keywords; deduplicates and trims;
-- NEVER overwrites or removes custom keywords added by Admin.
-- Preserves document IDs, timestamps, and editorial content.

BEGIN;
SET LOCAL client_encoding = 'UTF8';
SET LOCAL lock_timeout = '10s';

DO $preflight$
BEGIN
    IF to_regclass('legal_documents') IS NULL THEN
        RAISE EXCEPTION 'Missing legal_documents table: apply Flyway migrations first.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'legal_documents' AND column_name = 'keywords'
    ) THEN
        RAISE EXCEPTION 'Missing keywords column in legal_documents: apply Flyway V26 first.';
    END IF;
END
$preflight$;

LOCK TABLE legal_documents IN SHARE ROW EXCLUSIVE MODE;

WITH seed_catalog(slug, seed_keywords) AS (
    VALUES
        ('terms-of-service',
         ARRAY['terms of service', 'tos', 'điều khoản dịch vụ', 'quy định chung', 'tài khoản', 'người dùng', 'trách nhiệm', 'bảo mật']::text[]),

        ('privacy-policy',
         ARRAY['privacy policy', 'bảo mật', 'dữ liệu cá nhân', 'quyền riêng tư', 'thông tin tài khoản', 'lịch sử giao dịch', 'vị trí', 'camera']::text[]),

        ('station-owner-license-agreement',
         ARRAY['license agreement', 'hợp đồng thuê bao', 'chủ trạm', 'station owner', 'b2b', 'phí license', 'thuê bao trạm', 'vận hành trạm', 'phân công staff']::text[]),

        ('operational-regulations',
         ARRAY['quy chế', 'vận hành', 'operational regulations', 'khóa đồng thời', 'concurrency locking', 'pessimistic locking', 'double booking', 'tranh chấp']::text[]),

        ('booking-time-and-availability',
         ARRAY['đặt chỗ', 'booking', 'thời gian', 'khung giờ', 'lịch trống', 'availability', '60 phút', '30 phút', 'phiên qua ngày', 'giữ chỗ', 'hôm nay', 'ngày mai']::text[]),

        ('time-package-pricing-policy',
         ARRAY['giá gói', 'bảng giá', 'pricing', 'biểu giá', 'tou', 'kwh', 'ước tính', 'công suất', 'bảo toàn giá', 'không hoa hồng']::text[]),

        ('cancellation-and-refund-policy',
         ARRAY['refund', 'hoàn tiền', 'hoàn trả', 'cancellation', 'hủy đặt chỗ', 'grace period', 'ân hạn', 'no-show', 'vắng mặt', 'sự cố trạm', '100%']::text[]),

        ('qr-check-in-and-no-show-policy',
         ARRAY['qr code', 'check-in', 'quét mã', 'cổng sạc', 'no-show', 'vắng mặt', 'đến muộn', 'cửa sổ check-in', '15 phút', 'hết hạn']::text[]),

        ('payment-reconciliation-policy',
         ARRAY['thanh toán', 'payment', 'đối soát', 'reconciliation', 'hạn giữ chỗ', '10 phút', 'tiền thừa', 'tiền thiếu', 'mô phỏng']::text[]),

        ('station-incident-and-support-policy',
         ARRAY['sự cố', 'hỗ trợ', 'ticket', 'khiếu nại', 'incident', 'support', 'tranh chấp', 'lỗi trạm', 'hoàn tiền lỗi trạm']::text[]),

        ('station-discovery-and-eligibility-policy',
         ARRAY['tìm trạm', 'discovery', 'hiển thị trạm', 'điều kiện trạm', 'active', 'eligibility', 'bản đồ', 'trụ sạc', 'cổng sạc']::text[]),

        ('license-lifecycle-and-renewal-policy',
         ARRAY['vòng đời license', 'gia hạn', 'renewal', 'đình chỉ', 'suspended', 'expired', 'tháng lịch', 'năm lịch', 'subscription']::text[]),

        ('station-staff-access-policy',
         ARRAY['nhân viên', 'staff', 'phân công', 'assignment', 'phân quyền', 'giới hạn tài chính', 'vận hành trạm']::text[]),

        ('station-equipment-operation-policy',
         ARRAY['thiết bị', 'trụ sạc', 'cổng sạc', 'equipment', 'bảo trì', 'maintenance', 'công suất', 'type 2', 'ccs2', 'chademo', 'gbt']::text[]),

        ('owner-payout-and-adjustment-policy',
         ARRAY['chi trả', 'payout', 'doanh thu', 'chủ trạm', 'đối soát chi trả', 'điều chỉnh', 'adjustment', 'rút tiền', 'nghĩa vụ hoàn']::text[])
),
merged AS (
    SELECT
        s.slug,
        ARRAY(
            SELECT DISTINCT trim(kw)
            FROM unnest(COALESCE(d.keywords, '{}'::text[]) || s.seed_keywords) AS kw
            WHERE trim(kw) <> ''
            ORDER BY trim(kw)
        ) AS new_keywords
    FROM seed_catalog s
    JOIN legal_documents d ON d.slug = s.slug
),
updated AS (
    UPDATE legal_documents d
    SET keywords = m.new_keywords,
        updated_at = transaction_timestamp()
    FROM merged m
    WHERE d.slug = m.slug
      AND d.keywords IS DISTINCT FROM m.new_keywords
    RETURNING d.slug
)
SELECT
    (SELECT count(*) FROM seed_catalog) AS seed_catalog_size,
    (SELECT count(*) FROM updated) AS documents_updated;

-- Verification output
SELECT slug, array_length(keywords, 1) AS keyword_count, keywords
FROM legal_documents
WHERE slug IN (
    'terms-of-service',
    'privacy-policy',
    'station-owner-license-agreement',
    'operational-regulations',
    'booking-time-and-availability',
    'time-package-pricing-policy',
    'cancellation-and-refund-policy',
    'qr-check-in-and-no-show-policy',
    'payment-reconciliation-policy',
    'station-incident-and-support-policy',
    'station-discovery-and-eligibility-policy',
    'license-lifecycle-and-renewal-policy',
    'station-staff-access-policy',
    'station-equipment-operation-policy',
    'owner-payout-and-adjustment-policy'
)
ORDER BY slug;

COMMIT;
