-- V71: Publish the reviewed legal-copy corrections and English additions.
-- Existing rows update only while every editorial field still matches the
-- committed seed and the read-only production snapshot from 2026-11-17.
-- A mismatch (including an Admin edit) is preserved and reported as a warning.
-- Existing activation, effective date, keywords, IDs, and creation metadata are preserved.
-- updated_by is cleared on changed rows because the migration, not an Admin, made that edit.
-- New English slugs insert only when absent; existing or soft-deleted slugs are not resurrected.

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '30s';

DO $preflight$
BEGIN
    IF to_regclass('legal_documents') IS NULL THEN
        RAISE EXCEPTION 'Missing legal_documents; V25 must be applied before V71.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name = 'legal_documents' AND column_name = 'keywords') THEN
        RAISE EXCEPTION 'Missing legal_documents.keywords; V26 must be applied before V71.';
    END IF;
END
$preflight$;

LOCK TABLE legal_documents IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE chargeops_legal_copy_updates (
    slug text PRIMARY KEY,
    expected_updated_at timestamptz NOT NULL,
    expected_doc_type text NOT NULL,
    expected_target_audience text NOT NULL,
    expected_title text NOT NULL,
    expected_eyebrow text,
    expected_summary text,
    expected_content_md5 text NOT NULL,
    expected_version text NOT NULL,
    expected_locale text NOT NULL,
    desired_summary text,
    desired_content text,
    desired_version text NOT NULL
) ON COMMIT DROP;

INSERT INTO chargeops_legal_copy_updates (
    slug, expected_updated_at, expected_doc_type, expected_target_audience,
    expected_title, expected_eyebrow, expected_summary, expected_content_md5,
    expected_version, expected_locale, desired_summary, desired_content, desired_version
) VALUES
    ('booking-time-and-availability', timestamptz '2026-10-09T03:16:59.432558Z', 'OPERATIONAL_REGULATION', 'ALL', 'Quy định thời gian đặt chỗ và lịch khả dụng', 'ChargeOps · Đặt chỗ', 'Ngày bắt đầu hôm nay/ngày mai, đặt trước 60 phút, bước 30 phút, phiên qua ngày và phân biệt xem lịch với giữ chỗ.', 'b6fd9798e6d85080fd7691219e06b363', '4.9.0', 'vi', NULL, NULL, 'bkg067-2026-11-17-r1'),
    ('license-lifecycle-and-renewal-policy', timestamptz '2026-10-09T03:16:59.432558Z', 'LICENSE_AGREEMENT', 'OWNER', 'Quy định hiệu lực, gia hạn và đình chỉ thuê bao trạm', 'ChargeOps · Vòng đời license', 'Kỳ tháng/năm lịch, gia hạn tạo kỳ mới, đình chỉ không dừng đồng hồ và phân biệt hủy kỳ với chấm dứt quan hệ cấp phép.', '5ccda0ca042d097873c13e28e7347551', '4.9.0', 'vi', NULL, NULL, 'bkg067-2026-11-17-r1'),
    ('privacy-policy', timestamptz '2026-10-09T03:16:59.432558Z', 'PRIVACY_POLICY', 'ALL', 'Chính sách bảo mật dữ liệu', 'ChargeOps Privacy & Data Security', 'Mô tả dữ liệu phục vụ tài khoản, đặt chỗ, check-in và hỗ trợ; mục đích sử dụng và phạm vi truy cập theo quyền trên ChargeOps.', '1b5921233f3b9464b7df9ea6d70f9ff1', '4.9.0', 'vi', NULL, NULL, 'bkg067-2026-11-17-r1'),
    ('station-discovery-and-eligibility-policy', timestamptz '2026-10-09T03:16:59.432558Z', 'OPERATIONAL_REGULATION', 'ALL', 'Điều kiện hiển thị trạm và tiếp nhận đặt chỗ', 'ChargeOps · Điều kiện trạm', 'Tách trạng thái trạm, license, trụ và cổng; điều kiện tìm kiếm/booking; bảo toàn giao dịch đã được nhận hợp lệ.', '96c5143d9802c64afdc0dff7954869b2', '4.9.0', 'vi', NULL, NULL, 'bkg067-2026-11-17-r1'),
    ('station-owner-license-agreement', timestamptz '2026-10-09T03:16:59.432558Z', 'LICENSE_AGREEMENT', 'OWNER', 'Thỏa thuận cấp phép & Vận hành trạm sạc', 'Station Owner License Agreement', 'Thỏa thuận thuê bao phần mềm theo từng trạm, phí license, trách nhiệm vận hành và điều kiện nhận đặt chỗ mới trên ChargeOps.', '1af479b0da54231b67a74976abcebf23', '4.9.0', 'vi', NULL, $copy_station_owner_license_agreement$# Thỏa thuận cấp phép & Vận hành trạm sạc (B2B)

> Phạm vi: Diễn giải nghiệp vụ theo ChargeOps Vault, đối chiếu 08/09/2026. Các luồng Booking–Payment được mô tả theo thiết kế v4.9; tài liệu không xác nhận mọi chức năng đã triển khai. Quyền thao tác và tham số từng giao dịch căn cứ dữ liệu hệ thống trả về.


Thỏa thuận này quy định việc sử dụng phần mềm ChargeOps giữa nền tảng và **Chủ trạm sạc (Station Owner)**. **License là quyền sử dụng dịch vụ theo thuê bao cho từng trạm**, không phải giấy phép pháp lý cho hoạt động kinh doanh hoặc chứng nhận an toàn thiết bị.

---

### 1. Tư cách Chủ trạm & Quyền sở hữu trạm sạc
1. Chủ trạm là cá nhân hoặc tổ chức có quyền sở hữu hợp pháp hoặc quyền khai thác địa điểm đặt trạm sạc và các thiết bị trụ sạc xe điện liên quan.
2. Chủ trạm chịu trách nhiệm toàn bộ về pháp lý địa điểm, an toàn điện, phòng cháy chữa cháy và tuân thủ các quy định kỹ thuật của nhà nước tại địa phương nơi đặt trạm.

---

### 2. Gói License Subscription & Thanh toán B2B
1. Nền tảng ChargeOps cung cấp quyền truy cập phần mềm quản lý trạm thông qua mô hình cấp phép thuê bao (**License Subscription**) theo từng trạm:
   - Gói Tháng: 500.000 VNĐ / trạm / tháng.
   - Gói Năm: 5.000.000 VNĐ / trạm / năm.
2. Phí license được thanh toán B2B ngoài nền tảng. Quản trị viên đối chiếu việc mua thuê bao và ghi nhận kỳ license trên hệ thống; kỳ có ngày bắt đầu trong tương lai chờ đến thời điểm hiệu lực. Phí thuê bao tách biệt với tiền gói sạc của tài xế và không phải hoa hồng booking.

---

### 3. Nguyên tắc Độc lập giữa Trạng thái Trạm và Hiệu lực License
1. **Trạng thái Trạm (Station Status)** và **Hiệu lực License (License Validity)** là hai thuộc tính độc lập:
   - Trạm phải ở trạng thái **ACTIVE** và có license **ACTIVE**, đã đến ngày bắt đầu và chưa đến thời điểm hết hạn. Đây là điều kiện cần; hiển thị tìm kiếm còn yêu cầu trụ sạc đã kích hoạt và cổng sạc khả dụng. Khi tạo đặt chỗ, hệ thống kiểm tra thêm thiết bị cụ thể, giờ hoạt động, biểu giá và khung giờ trống.
2. **Bảo toàn giao dịch khi license mất hiệu lực**: License hết hạn, bị đình chỉ hoặc bị hủy làm trạm không còn xuất hiện trong tìm kiếm của tài xế và không nhận đặt chỗ mới. Thay đổi license không tự đổi trạng thái trạm hoặc thiết bị, không tự hủy hay hoàn tiền các đặt chỗ đã xác nhận; phiên đang chạy được tiếp tục. Đặt chỗ PENDING đã được nhận hợp lệ vẫn có thể hoàn tất thanh toán trong hạn giữ chỗ nếu trạm còn phục vụ được. Nguyên tắc này không loại bỏ việc xử lý riêng khi trạm thực sự gặp sự cố.

---

### 4. Trách nhiệm duy trì thiết bị và mã QR
1. Chủ trạm có trách nhiệm đảm bảo các trụ sạc, cổng sạc luôn trong tình trạng hoạt động tốt và cập nhật trạng thái vận hành trên hệ thống khi có sự cố kỹ thuật.
2. Chủ trạm cung cấp mã QR đúng cổng sạc theo quy trình của ChargeOps và hướng dẫn tài xế hoàn tất xác nhận check-in. Mã định danh cổng sạc không tự thay thế các bước xác thực check-in.
3. Nghiêm cấm mọi hành vi hoán đổi mã QR hoặc dán mã QR không đúng với cấu hình cổng sạc đã đăng ký.

---

### 5. Phân quyền Nhân viên vận hành trạm (Staff Assignment)
1. Chủ trạm mời nhân viên qua quy trình invitation để cấp vai trò tài khoản **STAFF**. Phân công còn **ACTIVE** xác định phạm vi trạm được vận hành; role **STAFF** không tự cấp quyền tại mọi trạm. Tài khoản **DRIVER** hiện hữu có thể được chuyển sang **STAFF** qua luồng phân công tương thích.
2. Nhân viên chỉ được phân quyền thao tác trong phạm vi các trạm sạc được chỉ định và không có quyền can thiệp vào cấu hình gói license hay thông tin tài chính của Chủ trạm.

## Căn cứ nghiệp vụ
- ChargeOps Vault: License/license-station-eligibility-policy.vi.
- ChargeOps Vault: License/license-admin-backend-api-design.
- ChargeOps Vault: glossary-and-business-rules-ssot (BR-ACC-01 supersession) and Station/staff-operations-backend-rebaseline-2026-10-05.vi.$copy_station_owner_license_agreement$, 'bkg067-2026-11-17-r1'),
    ('station-staff-access-policy', timestamptz '2026-10-09T03:16:59.432558Z', 'OPERATIONAL_REGULATION', 'OWNER', 'Quy định phân công nhân viên và phạm vi truy cập', 'ChargeOps · Nhân viên trạm', 'Staff là assignment ACTIVE theo trạm; tách quyền vận hành khỏi tiền, doanh thu, ngân hàng và chuyển hoàn.', '42011e8b7b0e7b9f6107fa91b4b5ade8', 'bkg067-2026-10-08-r1', 'vi', 'STAFF là vai trò tài khoản riêng; assignment ACTIVE xác định phạm vi vận hành theo trạm, tách khỏi quyền tài chính.', $copy_station_staff_access_policy$# Quy định phân công nhân viên và phạm vi truy cập

> Phạm vi: Quy tắc phân quyền theo Vault và thiết kế Booking v4.9. Nội dung không xác nhận mọi màn hình hoặc nghiệp vụ tài chính đã được triển khai.

## 1. Bản chất quyền nhân viên
STAFF là vai trò tài khoản riêng, được tạo qua quy trình mời. Assignment ACTIVE mới cấp quyền vận hành tại trạm được giao; role STAFF tự nó không cấp quyền trên mọi trạm. Tài khoản DRIVER hiện hữu có thể được chuyển sang STAFF qua luồng phân công tương thích.

Một người chỉ thao tác trong phạm vi trạm được giao. Máy chủ kiểm tra quyền cho từng yêu cầu, không dựa riêng vào việc giao diện có nút thao tác.

## 2. Công việc vận hành
Nhân viên tiếp cận thông tin vận hành, booking và phiếu hỗ trợ cần thiết theo quyền tại trạm, hỗ trợ giám sát và xác minh sự cố. Ghi nhận lỗi phải gắn với trạm và booking liên quan, có căn cứ để xử lý tiếp.

Khi phân công không còn ACTIVE, quyền phát sinh từ phân công đó không còn áp dụng cho yêu cầu mới. Không dùng quyền tại một trạm để xem hoặc sửa dữ liệu trạm khác.

## 3. Giới hạn dữ liệu tài chính
Dữ liệu trả cho nhân viên vận hành không bao gồm số tiền, doanh thu, thông tin thanh toán và trường ngân hàng. Nhân viên không quản lý license, thực hiện hoàn/chi trả hoặc tự xác nhận đã chuyển tiền.

Nhân viên xác minh lỗi trạm không thay thế quyết định hay thao tác tài chính có thẩm quyền. Phiếu đã được nhân viên xử lý không đồng nghĩa tài xế đã nhận hoàn.

## 4. Phân biệt chủ thể
Tài xế chỉ xem và thao tác booking, phiếu hỗ trợ của mình. Owner quản lý tài chính đúng trạm, nhận trách nhiệm thường lệ và thử lại hoàn Simulator khi hệ thống cho phép; không tự ghi kết quả chuyển tiền. Admin chỉ rà chính sách tài chính trong vụ việc cụ thể đã chuyển lên. Staff chỉ có quyền vận hành/bằng chứng, không quyết định trách nhiệm hoặc hoàn tiền. Phạm vi hiện tại không có chi trả Owner.

Khi phát hiện truy cập vượt phạm vi, cần ghi nhận để kiểm tra phân công và quyền; không chia sẻ tài khoản chủ trạm cho nhân viên để vượt giới hạn.

## Căn cứ nghiệp vụ
- ChargeOps Vault — glossary-and-business-rules-ssot: supersession BR-ACC-01; Station/staff-operations-backend-rebaseline-2026-10-05: Staff role, invitation và assignment.
- Booking/01-backend-design: mục 7, giới hạn quyền vận hành và trường tài chính.$copy_station_staff_access_policy$, 'bkg067-2026-11-17-r1'),
    ('terms-of-service', timestamptz '2026-10-09T03:16:59.432558Z', 'TERMS_OF_SERVICE', 'ALL', 'Điều khoản dịch vụ', 'ChargeOps Terms of Service', 'Điều khoản sử dụng ChargeOps: tài khoản, đặt gói sạc theo thời gian, thanh toán mô phỏng, hủy và hoàn tiền theo chính sách v4.9, check-in bằng mã QR.', 'a1d232ca2bd16e179d7f6a959828f779', 'bkg067-2026-10-08-r1', 'vi', NULL, $copy_terms_of_service$# Điều khoản dịch vụ ChargeOps

> Phạm vi: Diễn giải nghiệp vụ theo ChargeOps Vault, đối chiếu 08/09/2026; cập nhật phạm vi tài chính theo #34/#37 ngày 08/10/2026. Các luồng Booking–Payment được mô tả theo thiết kế v4.9; tài liệu không xác nhận mọi chức năng đã triển khai. Quyền thao tác và tham số từng giao dịch căn cứ dữ liệu hệ thống trả về.


Chào mừng bạn đến với nền tảng quản trị và đặt chỗ trạm sạc xe điện **ChargeOps**. Bằng việc đăng ký tài khoản và sử dụng dịch vụ trên ứng dụng di động hoặc cổng thông tin web, bạn đồng ý tuân thủ các điều khoản được quy định dưới đây.

---

### 1. Tài khoản và phân quyền người dùng
1. Tài khoản tài xế (**DRIVER**), chủ trạm (**OWNER**), nhân viên vận hành (**STAFF**) và quản trị viên (**ADMIN**) được cấp vai trò theo chức năng. Staff là vai trò tài khoản riêng, được tạo qua quy trình mời. Phân công còn **ACTIVE** xác định trạm mà nhân viên được phép vận hành; role **STAFF** không tự cấp quyền tại mọi trạm. Tài khoản **DRIVER** hiện hữu có thể được chuyển sang **STAFF** qua luồng phân công tương thích.
2. Hệ thống quản lý phiên đăng nhập và truy cập API bằng giao thức **OpenID Connect (OIDC)** kết hợp kiểm soát phân quyền chặt chẽ theo vai trò (RBAC) và phân công trạm cụ thể.
3. Người dùng có trách nhiệm bảo mật thông tin đăng nhập và thiết bị của mình, đồng thời báo cho bộ phận hỗ trợ khi phát hiện truy cập hoặc giao dịch bất thường.

---

### 2. Sử dụng tài khoản an toàn
1. Người dùng không chia sẻ mật khẩu, mã xác thực hoặc thông tin phiên đăng nhập cho người khác.
2. Không sử dụng tài khoản để truy cập dữ liệu ngoài phạm vi được cấp quyền, giả mạo giao dịch hoặc can thiệp trái phép vào hoạt động của trạm.

---

### 3. Đặt khung giờ sạc và giữ chỗ (Booking)
1. Tài xế lựa chọn trạm, cổng sạc (Connector) và gói thời gian. Ngày bắt đầu được chọn là hôm nay hoặc ngày mai; đặt trước ít nhất **60 phút**, giờ bắt đầu theo bước **30 phút**. Thời lượng có sàn **30 phút** và tăng theo bước **30 phút**; giới hạn áp dụng cụ thể được hiển thị khi đặt chỗ. Phiên có thể kéo dài qua ngày nếu đáp ứng giờ hoạt động của trạm.
2. Hệ thống kiểm tra điều kiện hoạt động của trạm, hiệu lực license, thiết bị, biểu giá, giờ hoạt động và lịch trống trước khi nhận đặt chỗ. Các yêu cầu đồng thời trên cùng cổng sạc được kiểm soát để tránh giữ chỗ chồng lấn.
3. Đặt chỗ mới ở trạng thái **Chờ thanh toán (PENDING)** và được giữ trong **10 phút kể từ lúc tạo**. Khi đến hạn mà thanh toán chưa được hệ thống xác nhận hợp lệ, đặt chỗ hết hạn (**EXPIRED**) và không còn giữ khung giờ. Khoản thanh toán đến muộn được đối soát riêng, không tự khôi phục đặt chỗ đã hết hạn.

---

### 4. Thanh toán và chính sách hủy, hoàn tiền
1. Tài xế thanh toán đủ giá gói thời gian trước khi sử dụng, không đặt cọc và không quyết toán theo điện năng thực đo. ChargeOps không thu phí dịch vụ hoặc hoa hồng trên booking. Trong phạm vi demo hiện tại, thu tiền và hoàn tiền được mô phỏng; không có chi trả cho chủ trạm trong phạm vi hiện tại; xác nhận trong demo không phải bằng chứng chuyển tiền qua ngân hàng thật.
2. **Ân hạn hủy 10 phút** được tính từ lần đầu hệ thống xác nhận thanh toán hợp lệ, khác với thời hạn giữ chỗ tính từ lúc tạo. Đặt chỗ **CONFIRMED**, chưa check-in, được hoàn **100% giá gói** khi yêu cầu hủy được hệ thống xử lý **trước cả hai mốc**: hết 10 phút ân hạn và giờ bắt đầu đã đặt. Tại đúng một trong hai mốc này, quyền hoàn do đổi ý không còn áp dụng.
3. Sau ân hạn, tài xế vẫn có thể hủy khi đặt chỗ còn **CONFIRMED**, nhưng hoàn **0%** do đổi ý. Vắng mặt (No-show) cũng hoàn **0%**, trừ trường hợp được xác nhận là lỗi trạm theo khoản dưới. Nếu số tiền hoàn thay đổi so với lúc xem trước, tài xế phải xác nhận lại trước khi hủy.
4. **Sự cố trạm được xác nhận** đủ điều kiện hoàn **100% giá gói** trong phạm vi MVP, kể cả sự cố giữa phiên hoặc được xác nhận sau khi đặt chỗ bị ghi nhận no-show. Không hoàn trùng phần giá gói đã hoàn; khoản thanh toán dư được đối soát riêng.
5. Xác nhận quyền được hoàn và thực hiện hoàn tiền là hai bước riêng. Việc hủy thành công hoặc đóng phiếu hỗ trợ không đồng nghĩa khoản hoàn đã hoàn tất; người dùng theo dõi trạng thái hoàn tiền của giao dịch.

---

### 5. Check-in QR và Trạng thái phần cứng
1. Tài xế quét mã QR hợp lệ của đúng cổng sạc đã đặt và xác nhận check-in trên ứng dụng. Quét mã hoặc xem trước thông tin chưa đồng nghĩa check-in thành công; hệ thống còn kiểm tra đặt chỗ, thời hạn và mã xác nhận. Nếu mã hết hạn hoặc đã được sử dụng, tài xế cần quét lại theo hướng dẫn.
2. Check-in được phép từ giờ bắt đầu đã đặt đến **trước thời điểm kết thúc đã đặt trừ 15 phút**. Tại đúng hạn chót này, đặt chỗ **CONFIRMED** chưa check-in bị ghi nhận vắng mặt (**CANCELLED**, lý do **NO_SHOW**). Đến muộn không làm lùi giờ kết thúc hoặc kéo dài phiên.
3. Trong phạm vi dự án hiện tại, trạng thái thiết bị và phiên sạc được mô phỏng theo mô hình trạng thái logic (Simulated Hardware State).

---

### 6. Hỗ trợ sự cố và giải quyết khiếu nại
1. Người dùng có thể tạo phiếu yêu cầu hỗ trợ (Ticket) đối với các sự cố liên quan đến giao dịch, thanh toán, tài khoản hoặc lỗi thiết bị tại trạm.
2. Chủ trạm và nhân viên được phân công còn hiệu lực tiếp nhận vấn đề vận hành trong phạm vi trạm. Owner quyết định trách nhiệm thường lệ và thử lại hoàn Simulator khi hệ thống cho phép; Admin chỉ rà chính sách tài chính trong vụ việc cụ thể đã chuyển lên. Staff ghi nhận bằng chứng kỹ thuật, không quyết định hoặc thực hiện hoàn tiền.

## Căn cứ nghiệp vụ
- ChargeOps Vault: Booking/README.
- ChargeOps Vault: Booking/01-backend-design.
- ChargeOps Vault: Booking/06-bkg-002-system-config-and-booking-policy.$copy_terms_of_service$, 'bkg067-2026-11-17-r1'),
    ('time-package-pricing-policy', timestamptz '2026-10-09T03:16:59.432558Z', 'OPERATIONAL_REGULATION', 'ALL', 'Chính sách giá gói thời gian và bảo toàn giá đặt chỗ', 'ChargeOps · Giá gói', 'Giá TOU, công suất và điện năng ước tính; giới hạn của hệ số 0,62; xác nhận lại khi giá thay đổi và giữ giá đã mua.', 'b64f37b18afd1a4c8826bdb02d386ec5', '4.9.0', 'vi', NULL, NULL, 'bkg067-2026-11-17-r1');

CREATE TEMP TABLE chargeops_legal_copy_additions (
    slug text PRIMARY KEY,
    doc_type text NOT NULL,
    target_audience text NOT NULL,
    title text NOT NULL,
    eyebrow text,
    summary text NOT NULL,
    content text NOT NULL,
    version text NOT NULL,
    locale text NOT NULL,
    keywords text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO chargeops_legal_copy_additions (
    slug, doc_type, target_audience, title, eyebrow, summary, content, version, locale, keywords
) VALUES
    ('booking-time-and-availability-en', 'OPERATIONAL_REGULATION', 'ALL', 'Booking Time and Availability Policy', 'ChargeOps · Booking v4.9', 'Today-or-tomorrow start dates, advance booking limits, operating hours, slot availability, and booking snapshots.', $copy_booking_time_and_availability_en$# Booking Time and Availability Policy

> Scope: These rules follow the ChargeOps Booking v4.9 design. The system supplies the applicable parameters for each transaction; this document does not confirm that every described flow is available in every runtime.

## 1. Start date, time, and duration

Under the v4.9 defaults, a Driver may choose a start date of today or tomorrow according to the system's business time in Vietnam (Asia/Ho_Chi_Minh). This is not permission to select any time within a rolling 48-hour window. A session that starts tomorrow may end on the following day if the station's operating schedule allows it.

The start time must be at least **60 minutes** after the system processes the request and must fall on the **30-minute grid**. For example, if it is 09:10, the earliest grid-aligned start is 10:30; that does not guarantee an available connector. The minimum duration is **30 minutes**, with additional duration in **30-minute** increments. Read the applicable limit from the system; do not treat the current 180-minute code limit as a permanently approved policy.

## 2. Operating hours and sessions crossing midnight

The entire charging interval must fit within valid service hours. Checking only the start and end is insufficient when the station closes during the interval. For a session crossing midnight, check the schedule for every calendar day it touches.

Two sessions may be adjacent at the previous session's end time without overlapping. Do not add a separate 10-minute turnaround buffer to the schedule; the 10-minute payment hold is a different rule.

## 3. Viewing availability does not reserve a slot

Connector availability and open status on search results describe the time of the search. They do not guarantee that a connector will remain free for the requested time. Viewing a price does not reserve a connector or lock the price. Only a successfully created **PENDING** booking holds the time slot during its payment window.

When a booking is created, the server checks eligibility, schedule, and price again. Two Drivers cannot hold overlapping times on the same connector. A Driver choosing different connectors at overlapping times may receive a warning; do not infer a general prohibition without an applicable policy.

## 4. Late arrival and configuration changes

Arriving late does not extend the session or move its scheduled end time. A configuration change does not automatically alter times or terms already saved to an existing booking. If a payment hold expires and the Driver books again, the new booking uses the data current at that time.

## Business references
- ChargeOps Vault: Booking/README, implicit-change invariants and D-LIMIT.
- ChargeOps Vault: Booking/01-backend-design, sections 2.1, 4, and 5.
- ChargeOps Vault: Booking/06-bkg-002-system-config-and-booking-policy, section 2.1.$copy_booking_time_and_availability_en$, 'bkg067-2026-11-17-r1', 'en', ARRAY['booking time', 'availability', 'today and tomorrow', '60 minutes', '30 minute grid']::text[]),
    ('license-lifecycle-and-renewal-policy-en', 'LICENSE_AGREEMENT', 'OWNER', 'Station License Validity, Renewal, and Suspension Policy', 'ChargeOps · License lifecycle', 'License effective periods, terminal and suspended states, renewal as a new term, and limits of cross-term controls.', $copy_license_lifecycle_and_renewal_policy_en$# Station License Validity, Renewal, and Suspension Policy

> Scope: This document summarizes the license lifecycle rules in the ChargeOps Vault. Cross-period license controls are described as a design template; this document does not confirm that the template has been fully implemented.

## 1. Meaning and term

A license is a per-station software subscription, not a government business permit. B2B license payment is separate from Driver transactions.

A monthly term follows one calendar month and an annual term follows one calendar year in the **Asia/Ho_Chi_Minh** time zone; neither is converted to a fixed 30 or 365 days. The effective interval includes startAt and excludes expiresAt. At the exact expiration time, the term no longer grants eligibility for new business.

## 2. States and reactivation

**PENDING** means a term has been created but is not yet effective. **ACTIVE** is usable only within its effective interval. **SUSPENDED** is a temporary suspension; the term's clock continues to run. **CANCELLED** and **EXPIRED** are terminal states and must not be changed back to **ACTIVE**.

Reactivation applies to a **SUSPENDED** term that is still within its validity period and meets the conditions for lifting the suspension. Reactivation is different from renewal.

## 3. Renewal creates a new term

Renewal does not extend or overwrite the old term. It creates a new term with its own identifier, a link to the source term, and a snapshot of the plan, fee, and Owner at creation time.

An **ACTIVE** term that is still valid may be renewed early into a **PENDING** term that starts when the current term ends. For an **EXPIRED** source term, a valid renewal starts a new term at the time of renewal. Renewal is not allowed from **PENDING**, **SUSPENDED**, or **CANCELLED** terms as a way to bypass management conditions.

The source must be the latest term and must not already have a successor. Each source term may have only one direct successor, even if that successor was cancelled. The current scope allows only one future term; it does not allow renewal from a **PENDING** term.

## 4. Cancelling a term and cross-term controls

Cancelling a term ends only the selected term; it does not automatically cancel its successor. Ending the station's license relationship is a broader decision and must not be described as simply cancelling one term.

The Vault describes station-level **COMPLIANCE_HOLD** and **REVOKED** states to block new grants, renewals, or reactivation across the term chain. These controls are a template that requires full implementation. Do not infer that a state on one term performs all of these station-level controls, or use a new term to bypass a suspension that remains in effect.

## 5. Driver transactions

A license change does not automatically change station, charger, or connector status, cancel a confirmed booking, or stop a session in progress. A real service incident or safety decision follows its separate operational process.

## Business references
- ChargeOps Vault: License/license-admin-backend-api-design, glossary and sections 7.4–7.7.
- ChargeOps Vault: License/license-station-eligibility-policy.vi, preservation of accepted transactions.$copy_license_lifecycle_and_renewal_policy_en$, 'bkg067-2026-11-17-r1', 'en', ARRAY['license lifecycle', 'renewal', 'subscription', 'suspended', 'expired']::text[]),
    ('operational-regulations-en', 'OPERATIONAL_REGULATION', 'ALL', 'ChargeOps Platform Operating Regulations', 'ChargeOps · Operations', 'Rules for payment holds, check-in and no-shows, package-price snapshots, station incidents, and role-based support.', $copy_operational_regulations_en$# ChargeOps Platform Operating Regulations

> Scope: This document summarizes ChargeOps operating rules based on the Vault, reviewed on 8 September 2026. Booking and payment behavior is described by the Booking v4.9 design; this does not confirm that every described feature is deployed. Applicable actions and transaction parameters come from the system response.

These rules apply to Drivers, station Owners, assigned Staff, and Admins within their respective permissions.

---

### 1. Preventing overlapping bookings
1. To prevent double bookings on the same charging connector, the system uses a pessimistic database lock while processing a booking-creation request.
2. Hold requests for the **same connector** are checked under the lock to prevent overlapping time ranges. This does not serialize every transaction across the platform.

---

### 2. Ten-minute payment hold
1. When a Driver's booking request is accepted, the system immediately holds the requested time range and sets the booking to **PENDING**.
2. The Driver has up to **10 minutes** to complete payment. The countdown starts when the booking is created.
3. If valid payment has not been confirmed by the deadline, the booking becomes **EXPIRED** and releases the time range. Late payments are reconciled separately. This payment-hold deadline is different from the cancellation grace period, which starts when payment is confirmed.

---

### 3. Check-in window and no-shows
1. The Driver must complete check-in with a valid QR code for the correct connector from the scheduled start time until **strictly before the scheduled end time minus 15 minutes**. Scanning the code or previewing details does not count as successful check-in.
2. At or after the cutoff, a **CONFIRMED** booking without check-in is recorded as **CANCELLED**, reason **NO_SHOW**; it is not a payment-hold expiry (**EXPIRED**). Late arrival does not extend the session, and ending early does not change the purchased package price.
3. A no-show booking releases its time range. Whether the connector can accept another booking still depends on equipment conditions and lead-time rules. A no-show receives a **0%** refund unless a station failure is confirmed as eligible for a refund.

---

### 4. Preserving the package price at booking time
1. The system recalculates the time-package price when a booking is created and stores the applicable price details with that booking. The station's per-kWh rate, connector power, and estimated energy are inputs to the package-price calculation; they are not a bill based on metered energy actually consumed. No additional service fee or booking commission is charged.
2. Changes to time-of-use (**TOU**) rates or the station's electricity price after a booking is created apply only to later bookings. They do not change the amount of a booking that was already created.

---

### 5. Technical incidents and responsibilities
1. When an incident is found, the Owner or assigned Staff record and handle it within their operating permissions. A connector that cannot serve uses **OFFLINE**; a charger may use **OFFLINE** or **MAINTENANCE**, depending on the situation. Routine maintenance must account for bookings already sold. An emergency incident is recorded separately with its reason and affected bookings.
2. A confirmed station failure may qualify for a **100% package-price refund** under the MVP, including a failure during a session; the same amount is not refunded twice. Creating or closing a support ticket does not itself transfer money. The Owner handles ordinary responsibility decisions; Admin makes a financial decision only in a specific escalated case; Staff records technical evidence. Refund core uses the Simulator, and the Owner may retry when the system permits. A simulated result is not evidence of a real bank transfer.

## Business references
- ChargeOps Vault: Booking/README.
- ChargeOps Vault: Booking/01-backend-design.
- ChargeOps Vault: Booking/06-bkg-002-system-config-and-booking-policy.
- ChargeOps Vault: Connector/chargepoint-connector-invariants-t17, connector states.
$copy_operational_regulations_en$, 'bkg067-2026-10-08-r1', 'en', ARRAY['operational regulations', 'booking overlap', 'concurrency', 'check-in deadline', 'no-show', 'refund']::text[]),
    ('privacy-policy-en', 'PRIVACY_POLICY', 'ALL', 'Data Privacy Policy', 'ChargeOps · Privacy', 'How ChargeOps uses account, booking, check-in, and support data, including its purposes and access scope.', $copy_privacy_policy_en$# ChargeOps Data Privacy Policy

> Scope: This policy describes how ChargeOps collects, stores, uses, and protects personal data and transaction history when users use the service. Access and applicable transaction details follow the system records and permissions.

---

### 1. Account data
1. Account details such as name, email address, phone number, and access role are used for sign-in, communication, and service operations.
2. Users should protect their sign-in information and contact support if they suspect unauthorized account access.

---

### 2. Use of transaction data
1. Booking details, the package price saved when a booking is made, payment and refund status, and check-in history are used to provide the service, reconcile transactions, and resolve complaints.
2. Support-ticket content and incident findings are used to verify issues and determine appropriate handling. Simulated payment data is used for testing and demonstrations and does not prove that a real bank transaction occurred.

---

### 3. Data access scope
1. Drivers access their own bookings and support tickets. Station owners access data and financial information for their stations as permitted by their assigned scope.
2. Staff with an active assignment may access only operational data and support tickets for the assigned station. This access does not include amounts, revenue, payment details, or bank-account information.
3. Administrators access data for administrative, support, dispute-resolution, and reconciliation functions. Access is checked by the server and is not enforced only by the interface.

---

### 4. Location and QR scanning
1. Nearby-station search uses the location provided for that search request. QR codes identify a connector and support check-in validation.
2. Users manage location and camera permissions in their device settings. Features that require a permission may be unavailable when it is denied.

---

### 5. Data requests and support
1. Users may review data within their account scope and contact support if information is inaccurate or appears to have been accessed without authorization.
2. Requests to correct or delete data are reviewed together with transaction history and obligations still being processed. Submitting a request does not mean all transaction history has been deleted.

## Business references
- ChargeOps Vault: Booking/08-legal-documents-ssot-and-pragmatic-search.
- ChargeOps Vault: Booking/01-backend-design.$copy_privacy_policy_en$, 'bkg067-2026-11-17-r1', 'en', ARRAY['privacy policy', 'personal data', 'account data', 'transaction history', 'location', 'camera']::text[]),
    ('station-discovery-and-eligibility-policy-en', 'OPERATIONAL_REGULATION', 'ALL', 'Station Discovery and Booking Eligibility Policy', 'ChargeOps · Station eligibility', 'Distinguishes station approval, license validity, equipment readiness, discovery visibility, and booking acceptance.', $copy_station_discovery_and_eligibility_policy_en$# Station Discovery and Booking Eligibility Policy

> Scope: This policy summarizes the Station–License rules and the discovery/booking design in the ChargeOps Vault. The Booking v4.9 design does not confirm that every described behavior is deployed.

## 1. Independent conditions

Station status represents platform approval and station operations. A license represents the right to accept new business through the platform. Charger activation and connector runtime status represent separate equipment conditions. These states must not be treated as one synchronized status.

For example, a station can be **ACTIVE** while its license is **SUSPENDED**, its charger is activated, and its connector is **AVAILABLE**. Equipment readiness does not make the license valid again.

## 2. Appearing in Driver search

A station must be **ACTIVE**, have an **ACTIVE** license within its effective period—from startAt up to but not including expiresAt—and have an activated charger with an **AVAILABLE** connector. A license whose status still says **ACTIVE** is not eligible after expiresAt, even if a status-update job is delayed.

Open status, distance, connector availability, and a displayed station rate are search information. A per-kWh rate is not the total price of a time package, and an available connector count does not guarantee an open slot during the Driver's requested time.

## 3. Checks when creating a booking

Booking creation also checks the selected connector, the complete operating-hours interval, rates, and open time slots. Seeing a station in search does not replace server-side checks; a request may be rejected if conditions changed after the search.

The Booking v4.9 design distinguishes a connector that is **IN_USE** now from future schedule availability. Do not infer that a connector being busy now makes every later time unavailable. A connector that cannot serve a booking or a charger under maintenance must still be blocked under the applicable equipment rules.

## 4. When a license is no longer usable

A station with an unusable license cannot accept new bookings and does not appear in Driver discovery. Owners and Admins may still manage data within their authorized scope. A license change does not automatically cancel confirmed bookings or stop a session already in progress.

A valid **PENDING** booking may complete payment during its hold period if the station can still serve it. Preserving a booking because of a license change does not mean ignoring an actual service incident, a safety suspension, or a separate operational decision.

## Business references
- ChargeOps Vault: License/license-station-eligibility-policy.vi, policy sections 4–7.
- ChargeOps Vault: Discovery/driver-station-discovery-flow.vi, search, details, and availability (FR02/FR03/FR04).
- ChargeOps Vault: Booking/01-backend-design, sections 5 and 7.$copy_station_discovery_and_eligibility_policy_en$, 'bkg067-2026-11-17-r1', 'en', ARRAY['station discovery', 'eligibility', 'active station', 'effective license', 'connector availability']::text[]),
    ('station-equipment-operation-policy-en', 'OPERATIONAL_REGULATION', 'OWNER', 'Station Equipment Operation and Maintenance Policy', 'ChargeOps · Station equipment', 'Equipment hierarchy, connector power and type constraints, status distinctions, maintenance, and limits of simulated readings.', $copy_station_equipment_operation_policy_en$# Station Equipment Operation and Maintenance Policy

> Scope: These rules summarize ChargeOps equipment constraints in the Vault and the booking design for maintenance. Logical or simulated status is not a safety certification or a measurement of actual power delivery. This document does not confirm that every described screen or workflow is deployed.

## 1. Equipment hierarchy and identifiers

A station contains charge points, and each charge point contains its own connectors. Charge point and connector codes identify equipment within the management scope. Owners maintain equipment details and provide the QR code for the correct connector; do not swap codes in a way that could make a Driver identify the wrong location.

Station approval, license validity, and equipment activation are separate steps. Having a license does not mean every charge point or connector is ready to serve Drivers.

## 2. Hardware configuration

Under T17, a connector's declared power must be between **3 and 360 kW**. If a charge point's maximum power is declared, it must be between **3 and 720 kW** and must not be lower than the declared power of any connector attached to that charge point. This rule compares each connector with the charge point; it does not imply a total across all connectors where the domain model has not defined one.

In the project model, TYPE2 corresponds to AC; CCS2, CHAdeMO, and GB/T correspond to DC. Connector type, current, and power must be consistent. Hardware fields that are immutable under the equipment lifecycle cannot be changed like a display name.

## 3. Distinguishing equipment states

Provisioning or activation status is different from operational status. A charge point has an operational status of **AVAILABLE**, **OFFLINE**, or **MAINTENANCE**. A connector has a runtime status of **AVAILABLE**, **IN_USE**, or **OFFLINE**. Do not use **FAULTED** or **MAINTENANCE** as connector runtime values when the enum does not support them.

An expired license does not automatically set a connector to **OFFLINE** or a charge point to **MAINTENANCE**. A connector marked **AVAILABLE** does not replace the station approval and license requirements.

## 4. Maintenance and incidents

Schedule, price, or routine maintenance changes must account for bookings already sold and preserve prices already committed to those bookings. An emergency incident is handled separately, with a reason, history, and a list of affected bookings; it must not be forced through a routine maintenance operation that is blocked.

If a station cannot serve a Driver, coordinate verification and booking handling; do not assume the Driver is automatically moved to another connector. A station failure confirmed under the MVP may qualify for a full package-price refund, including a failure during a session.

## 5. Limits of the data

Declared power, logical equipment status, and simulated session data do not prove how much electricity a Driver actually received. Do not present them as equipment safety certification or a performance guarantee.

## Business references
- ChargeOps Vault: Connector/chargepoint-connector-invariants-t17, sections 1–3, connector power limits and connector compatibility.
- ChargeOps Vault: Charge Point/api_design_document_chargepoint_connector_admin_apis, sections 1–2, equipment hierarchy, status, and provisioning.
- ChargeOps Vault: Booking/01-backend-design, section 7, maintenance and protection of sold bookings.
$copy_station_equipment_operation_policy_en$, '4.9.0', 'en', ARRAY['station equipment', 'charging station', 'connector', 'maintenance', 'power rating', 'simulator']::text[]),
    ('station-owner-license-agreement-en', 'LICENSE_AGREEMENT', 'OWNER', 'Station Owner License Agreement', 'ChargeOps · Station License', 'B2B station subscription, license fees, operating responsibilities, and conditions for accepting new bookings.', $copy_station_owner_license_agreement_en$# Station Owner License Agreement (B2B)

> Scope: This document summarizes the station subscription rules under ChargeOps Booking v4.9. It does not confirm that every described function is available in every runtime. The system response and the saved terms of each transaction determine applicable actions and parameters.

This agreement governs the use of ChargeOps software between the platform and the **charging station Owner**. A **license is a per-station software subscription**; it is not a legal permit to operate a charging business or a certification of equipment safety.

---

### 1. Station Owner status and station rights
1. The Owner is an individual or organization with lawful ownership or operating rights for the site where the charging station and related equipment are located.
2. The Owner is responsible for the site's legal compliance, electrical safety, fire prevention and firefighting, and applicable local technical requirements.

---

### 2. License subscription and B2B payment
1. ChargeOps provides station-management software access through a per-station subscription:
   - Monthly plan: **VND 500,000 / station / month**.
   - Annual plan: **VND 5,000,000 / station / year**.
2. License fees are paid B2B outside the platform. An administrator reconciles the purchase and records the license period in the system; a period with a future start date remains pending until it becomes effective. The subscription fee is separate from a Driver's charging package price and is not a booking commission.

---

### 3. Station status is separate from license validity
1. **Station status** and **license validity** are separate attributes:
   - A station must be **ACTIVE** and have an **ACTIVE** license whose start date has arrived and expiration time has not arrived. These are necessary conditions. Search visibility also requires an activated charger and an available connector. When creating a booking, the system also checks the specific equipment, operating hours, rates, and open time slots.
2. **Preserving accepted bookings when a license becomes invalid:** An expired, suspended, or cancelled license makes the station unavailable in Driver search and prevents new bookings. A license change does not automatically change station or equipment status, cancel or refund confirmed bookings, or stop a session already in progress. A valid **PENDING** booking may still complete payment within its hold period if the station can continue serving it. This does not remove the need to handle a separate station incident when service is actually disrupted.

---

### 4. Equipment maintenance and QR codes
1. The Owner is responsible for keeping chargers and connectors in working condition and updating their operational status on the platform when a technical incident occurs.
2. The Owner provides the QR code for the correct connector and guides Drivers through check-in confirmation. A connector identifier does not replace the check-in authentication steps.
3. Swapping QR codes or displaying a QR code that does not match the registered connector configuration is prohibited.

---

### 5. Staff access
1. The Owner invites staff through the invitation flow to grant the account role **STAFF**. An **ACTIVE** assignment determines the station the staff member may operate; the **STAFF** role alone does not grant access to every station. An existing **DRIVER** account may be promoted to **STAFF** through the compatible assignment flow.
2. Staff may act only within the stations assigned to them and cannot change the Owner's license plan or access the Owner's financial information.

## Business references
- ChargeOps Vault: License/license-station-eligibility-policy.vi.
- ChargeOps Vault: License/license-admin-backend-api-design.
- ChargeOps Vault: glossary-and-business-rules-ssot, BR-ACC-01 supersession.
- ChargeOps Vault: Station/staff-operations-backend-rebaseline-2026-10-05.vi.
- ChargeOps Vault: Booking/01-backend-design, Booking/README.$copy_station_owner_license_agreement_en$, 'bkg067-2026-11-17-r1', 'en', ARRAY['license agreement', 'station owner', 'b2b subscription', 'staff assignment', 'platform service']::text[]),
    ('station-staff-access-policy-en', 'OPERATIONAL_REGULATION', 'OWNER', 'Station Staff Assignment and Access Policy', 'ChargeOps · Station Staff', 'Distinguishes the STAFF account role from an active station assignment and defines operational and financial access boundaries.', $copy_station_staff_access_policy_en$# Station Staff Assignment and Access Policy

> Scope: These rules follow the current ChargeOps role and station-assignment model. They do not confirm that every described screen or financial workflow is available in every runtime.

## 1. What Staff access means

**STAFF** is a distinct account role created through an invitation flow. An **ACTIVE** assignment grants operational access only to the assigned station; the **STAFF** role alone does not grant access to every station. An existing **DRIVER** account may be promoted to **STAFF** through the compatible assignment flow.

A staff member may act only within the assigned station. The server checks authorization for each request; access is not granted merely because an action appears in the interface.

## 2. Station operations

Staff may access operational information, bookings, and support tickets needed to monitor the assigned station and investigate incidents. Incident findings should identify the relevant station and booking and include supporting evidence.

When an assignment is no longer **ACTIVE**, permissions from that assignment no longer apply to new requests. An assignment at one station does not grant access to another station.

## 3. Financial data boundaries

In the station-operations scope, Staff responses do not include amounts, station revenue, payment information, or bank-account fields. Staff cannot manage licenses, execute refunds or payouts, or confirm that a transfer occurred.

A technical finding about a station incident is evidence. It does not replace an authorized responsibility decision or financial action. A ticket handled by Staff does not mean the Driver has received a refund.

## 4. Distinguishing account roles and actors

Drivers view and manage their own bookings and support tickets. Owners manage finances for their stations, may accept ordinary responsibility, and may retry a Simulator refund when the system permits; none of these actions proves a bank transfer. Admins review financial policy only in a specific escalated case. Staff are limited to station operations and technical evidence; they do not decide financial responsibility or issue refunds.

If access appears to exceed the assigned scope, review the assignment and authorization. Do not share an Owner account with Staff to bypass access boundaries.

## Business references
- ChargeOps Vault: glossary-and-business-rules-ssot, BR-ACC-01 supersession and BR-ACC-04/05.
- ChargeOps Vault: Station/staff-operations-backend-rebaseline-2026-10-05.vi.
- ChargeOps Vault: Booking/01-backend-design, section 7.$copy_station_staff_access_policy_en$, 'bkg067-2026-11-17-r1', 'en', ARRAY['staff role', 'staff invitation', 'station assignment', 'active assignment', 'financial access']::text[]),
    ('terms-of-service-en', 'TERMS_OF_SERVICE', 'ALL', 'Terms of Service', 'ChargeOps Terms of Service', 'Account roles, station-scoped access, time-package bookings, Simulator payments and refunds, QR check-in, and support responsibilities.', $copy_terms_of_service_en$# ChargeOps Terms of Service

> Scope: This document summarizes ChargeOps booking and payment rules under Booking v4.9. It does not confirm that every described function is available in every runtime. The system response and the saved terms of each transaction determine applicable actions and parameters.

Welcome to **ChargeOps**, a platform for managing and booking electric vehicle charging stations. By registering for an account and using our mobile app or web portal, you agree to follow these terms.

---

### 1. Accounts and roles
1. Drivers (**DRIVER**), station owners (**OWNER**), operating staff (**STAFF**), and administrators (**ADMIN**) receive account roles according to their functions. **STAFF** is a distinct account role created through an invitation process. An **ACTIVE** station assignment determines which station a staff member may operate; the **STAFF** role alone does not grant access to every station. Existing **DRIVER** accounts may be promoted to **STAFF** through the compatible assignment flow.
2. Sign-in and API access use OpenID Connect (OIDC) together with role-based access control (RBAC) and station-specific assignments.
3. Users must protect their credentials and devices and notify support if they suspect unauthorized access or activity.

---

### 2. Account security
1. Do not share passwords, authentication codes, or session credentials with others.
2. Do not use an account to access data outside its authorized scope, falsify transactions, or interfere with station operations.

---

### 3. Charging bookings and time slots
1. Drivers choose a station, connector, start date, start time, and time package. The start date must be today or tomorrow; bookings must be made at least **60 minutes** in advance, in **30-minute** increments. The minimum duration is **30 minutes**, also in **30-minute** increments. The applicable limit is shown during booking. A session may cross midnight if the station schedule allows it.
2. Before accepting a booking, the system checks station availability, license, equipment, rates, operating hours, and open time slots. Concurrent requests for the same connector are controlled to prevent overlapping bookings.
3. A new booking starts as **PENDING** and holds the slot for **10 minutes from creation**. If valid payment is not confirmed before the hold expires, the booking becomes **EXPIRED** and releases the slot. A late payment is reconciled separately and does not automatically restore the expired booking.

---

### 4. Payment, cancellation, and refunds
1. Drivers pay the full time-package price before use. The service does not require a deposit or settle charges by metered energy. ChargeOps does not collect a service fee or booking commission. In the current demo scope, collections and refunds are simulated; there is no Owner payout, and a demo confirmation is not proof of a real bank transfer.
2. The default **10-minute cancellation grace period** starts at the first valid payment confirmation. It is separate from the payment hold that starts when a booking is created. A **CONFIRMED** booking without check-in receives a **100% package-price refund** only when the server processes the cancellation before both the grace-period deadline and the scheduled start time. At either deadline, the change-of-mind refund no longer applies.
3. After the grace period, a **CONFIRMED** booking may still be cancelled for a change of mind, but the refund is **0%**. A no-show also receives **0%**, except when station failure is confirmed under the rule below. If the refund amount has changed since preview, the driver must consent again before cancellation.
4. A **confirmed station failure** may qualify for a **100% package-price refund** within the MVP, including a failure during a session or one confirmed after a no-show. The same package price is not refunded twice; excess payment is reconciled separately.
5. Refund eligibility and refund execution are separate steps. A successful cancellation or a closed support ticket does not mean a refund has completed. Users should check the transaction refund status.

---

### 5. QR check-in and equipment status
1. Drivers scan a valid QR code for the booked connector and confirm check-in in the app. Scanning or previewing the details alone does not mean check-in succeeded; the system also checks the booking, deadline, and confirmation code. If the code expires or has already been used, follow the app instructions to scan again.
2. Check-in is allowed from the scheduled start until **before the scheduled end time minus 15 minutes**. At the cutoff, a **CONFIRMED** booking without check-in is recorded as a no-show (**CANCELLED**, reason **NO_SHOW**). Late arrival does not move the scheduled end or extend the session.
3. In the current project scope, equipment and charging-session states are simulated logical states.

---

### 6. Support and incident responsibility
1. Users may submit a support ticket about a transaction, payment, account, or station-equipment issue.
2. The Owner and staff with an active assignment may handle operational issues within the assigned station. The Owner handles ordinary responsibility decisions and may retry a Simulator refund when permitted. Admin reviews financial policy only in a specific escalated case. Staff record technical evidence but do not decide or execute refunds.

## Business references
- ChargeOps Vault: glossary-and-business-rules-ssot, BR-ACC-01 supersession.
- ChargeOps Vault: Station/staff-operations-backend-rebaseline-2026-10-05.vi, STAFF role, invitation, and assignment.
- ChargeOps Vault: Booking/README, Booking/01-backend-design, and Booking/06-bkg-002-system-config-and-booking-policy.$copy_terms_of_service_en$, 'bkg067-2026-11-17-r1', 'en', ARRAY['terms of service', 'account roles', 'station access', 'time-package booking', 'simulator refunds']::text[]),
    ('time-package-pricing-policy-en', 'OPERATIONAL_REGULATION', 'ALL', 'Time-Package Pricing Policy', 'ChargeOps · Booking v4.9', 'Time-package pricing, TOU rate selection, the unverified 0.62 estimate, price reconfirmation, and saved booking prices.', $copy_time_package_pricing_policy_en$# Time-Package Pricing and Booking Price Protection

> Scope: This document describes the time-package pricing model in the ChargeOps Booking v4.9 design. It is not evidence of metered energy or confirmation that every pricing API has been deployed.

## 1. What the Driver buys

The Driver prepays for a time-based connector package. This is not a deposit and is not settled against energy measured in kWh. ChargeOps does not charge a service fee or booking commission; the station Owner's license subscription is separate.

The Owner sets rates per kWh and by time-of-use (TOU) period. The applicable TOU rate is selected according to the scheduled charging interval, not the time the price screen is opened. A package spanning multiple rate periods is split into corresponding price lines.

## 2. Booking v4.9 calculation

For each line, the connector's rated power is multiplied by the session duration in hours and by **0.62** to produce estimated energy. The estimate is rounded using HALF_UP to one decimal place. The rounded estimate is multiplied by the rate per kWh; the line amount is then rounded using HALF_UP to the nearest **VND 1,000**. The package total is the sum of its line amounts and is not rounded again.

Example: one line for a **60 kW** connector, **60 minutes**, and a rate of **VND 3,400/kWh** produces estimated energy of **37.2 kWh**. The amount before rounding is **VND 126,480**, so the line price is **VND 126,000**. This is a calculation example, not a universal rate for every station.

## 3. Limits of the estimate

The **0.62** factor is a prototype assumption and has not been calibrated against real charging data. It is not a validated efficiency, battery state, or promise that the calculated amount of energy will be delivered. Rated connector power does not prove the power received by a vehicle throughout a session.

## 4. Price confirmation and booking snapshot

The price shown while selecting a time is an estimate. The server calculates it again when the Driver continues and when the booking is created. If the amount, price breakdown, or policy differs from what the Driver accepted, the Driver must review and confirm again; the system must not create a booking using a newly changed price without that confirmation.

After a **PENDING** booking is created successfully, its package price and calculation basis are saved for that transaction. An Owner's later rate change does not change an existing booking's saved price. If the payment hold expires, a new booking uses the then-current data. Ending a session early does not automatically reduce the package price.

## Business references
- ChargeOps Vault: Booking/01-backend-design, sections 2, 2.1, and 2.3; calculation, price confirmation, and origin of the 0.62 factor.
- ChargeOps Vault: Booking/README, prepaid time packages, no booking fee, and decision D-PRICE.$copy_time_package_pricing_policy_en$, 'bkg067-2026-11-17-r1', 'en', ARRAY['time-package price', 'pricing', 'time of use', 'tou', 'kwh estimate', 'weekly schedule']::text[]);

DO $publish$
DECLARE
    skipped_existing text;
    existing_additions text;
    updated_count integer := 0;
    inserted_count integer := 0;
BEGIN
    SELECT string_agg(u.slug, ', ' ORDER BY u.slug)
      INTO skipped_existing
      FROM chargeops_legal_copy_updates u
      LEFT JOIN legal_documents d ON d.slug = u.slug
     WHERE d.id IS NOT NULL
       AND (d.deleted_at IS NOT NULL
         OR d.updated_at IS DISTINCT FROM u.expected_updated_at
         OR md5(d.content) IS DISTINCT FROM u.expected_content_md5
         OR ROW(d.doc_type::text, d.target_audience::text, d.title::text, d.eyebrow::text, d.summary::text, d.version::text, d.locale::text)
            IS DISTINCT FROM ROW(u.expected_doc_type, u.expected_target_audience, u.expected_title, u.expected_eyebrow, u.expected_summary, u.expected_version, u.expected_locale));

    UPDATE legal_documents d
       SET summary = COALESCE(u.desired_summary, d.summary),
           content = COALESCE(u.desired_content, d.content),
           version = u.desired_version,
           updated_at = transaction_timestamp(),
           updated_by = NULL
      FROM chargeops_legal_copy_updates u
     WHERE d.slug = u.slug
       AND d.deleted_at IS NULL
       AND d.updated_at = u.expected_updated_at
       AND md5(d.content) = u.expected_content_md5
       AND ROW(d.doc_type::text, d.target_audience::text, d.title::text, d.eyebrow::text, d.summary::text, d.version::text, d.locale::text)
           IS NOT DISTINCT FROM ROW(u.expected_doc_type, u.expected_target_audience, u.expected_title, u.expected_eyebrow, u.expected_summary, u.expected_version, u.expected_locale);
    GET DIAGNOSTICS updated_count = ROW_COUNT;

    IF skipped_existing IS NOT NULL THEN
        RAISE WARNING 'V71 preserved existing legal rows that no longer match the reviewed baseline: %', skipped_existing;
    END IF;

    SELECT string_agg(a.slug, ', ' ORDER BY a.slug)
      INTO existing_additions
      FROM chargeops_legal_copy_additions a
      JOIN legal_documents d ON d.slug = a.slug;

    INSERT INTO legal_documents (
        slug, doc_type, target_audience, title, eyebrow, summary, content, version, locale,
        keywords, is_active, effective_from, created_at, updated_at
    )
    SELECT slug, doc_type, target_audience, title, eyebrow, summary, content, version, locale,
           keywords, true, transaction_timestamp(), transaction_timestamp(), transaction_timestamp()
      FROM chargeops_legal_copy_additions
    ON CONFLICT (slug) DO NOTHING;
    GET DIAGNOSTICS inserted_count = ROW_COUNT;

    IF existing_additions IS NOT NULL THEN
        RAISE WARNING 'V71 did not replace existing or soft-deleted legal slugs; preserved: %', existing_additions;
    END IF;
    RAISE NOTICE 'V71 legal publication completed: % existing rows updated, % new English rows inserted.', updated_count, inserted_count;
END
$publish$;
