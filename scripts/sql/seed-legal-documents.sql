-- ChargeOps: manual legal/business catalog, UTF-8; not a Flyway migration.
-- Apply V25 first. Execute the WHOLE file in a fresh session.
-- psql -X -v ON_ERROR_STOP=1 -d chargeops -f scripts/sql/seed-legal-documents.sql
-- Rerun deliberately overwrites editorial fields of the listed slugs.
-- Preserves IDs, creation audit, effective dates, inactive and soft-delete state.
-- Does not delete other documents, change system_configs or repair Flyway history.
-- New documents are active. Review the catalog before running on a shared DB.
-- Version 4.9.0 denotes the business baseline, not append-only revision history.

BEGIN;
SET LOCAL client_encoding = 'UTF8';
SET LOCAL lock_timeout = '10s';
DO $preflight$
BEGIN
    IF to_regclass('legal_documents') IS NULL THEN
        RAISE EXCEPTION 'Missing legal_documents: apply Flyway V25 first.';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'legal_documents'::regclass
                 AND conname = 'uq_legal_docs_type_version_locale') THEN
        RAISE EXCEPTION 'The legacy type/version/locale constraint blocks the catalog. Apply the schema-only V25 on a fresh database; use a new schema migration if an older V25 was already applied.';
    END IF;
END
$preflight$;

-- Serialize catalog/admin writes while allowing reads. Commit all or nothing.
LOCK TABLE legal_documents IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE chargeops_legal_seed (
    slug varchar(100) PRIMARY KEY,
    doc_type varchar(50) NOT NULL,
    target_audience varchar(30) NOT NULL,
    title varchar(255) NOT NULL,
    eyebrow varchar(100),
    summary text NOT NULL,
    content text NOT NULL,
    version varchar(30) NOT NULL,
    locale varchar(10) NOT NULL,
    CHECK (doc_type IN ('TERMS_OF_SERVICE','PRIVACY_POLICY','LICENSE_AGREEMENT','OPERATIONAL_REGULATION')),
    CHECK (target_audience IN ('ALL','DRIVER','OWNER'))
) ON COMMIT DROP;

-- Source: docs/ChargeOps/Booking/README.md
-- Source: docs/ChargeOps/Booking/01-backend-design.md
-- Source: docs/ChargeOps/Booking/06-bkg-002-system-config-and-booking-policy.md
INSERT INTO chargeops_legal_seed VALUES (
'terms-of-service',
'TERMS_OF_SERVICE',
'ALL',
'Điều khoản dịch vụ',
'ChargeOps Terms of Service',
'Điều khoản sử dụng ChargeOps: tài khoản, đặt gói sạc theo thời gian, thanh toán mô phỏng, hủy và hoàn tiền theo chính sách v4.9, check-in bằng mã QR.',
$document$# Điều khoản dịch vụ ChargeOps

> Phạm vi: Diễn giải nghiệp vụ theo ChargeOps Vault, đối chiếu 08/09/2026. Các luồng Booking–Payment được mô tả theo thiết kế v4.9; tài liệu không xác nhận mọi chức năng đã triển khai. Quyền thao tác và tham số từng giao dịch căn cứ dữ liệu hệ thống trả về.


Chào mừng bạn đến với nền tảng quản trị và đặt chỗ trạm sạc xe điện **ChargeOps**. Bằng việc đăng ký tài khoản và sử dụng dịch vụ trên ứng dụng di động hoặc cổng thông tin web, bạn đồng ý tuân thủ các điều khoản được quy định dưới đây.

---

### 1. Tài khoản và phân quyền người dùng
1. Tài khoản tài xế (**DRIVER**), chủ trạm (**STATION_OWNER**) và quản trị viên (**ADMIN**) được cấp quyền theo chức năng. Nhân viên vận hành (Staff) được chủ trạm phân công vào trạm; quyền Staff chỉ có hiệu lực khi phân công còn **ACTIVE**, không phải một vai trò tài khoản độc lập.
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
1. Tài xế thanh toán đủ giá gói thời gian trước khi sử dụng, không đặt cọc và không quyết toán theo điện năng thực đo. ChargeOps không thu phí dịch vụ hoặc hoa hồng trên booking. Trong phạm vi demo hiện tại, thu tiền, hoàn tiền và chi trả được mô phỏng; xác nhận trong demo không phải bằng chứng chuyển tiền qua ngân hàng thật.
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
2. Chủ trạm và nhân viên được phân công còn hiệu lực tiếp nhận vấn đề vận hành trong phạm vi trạm. Quản trị viên xử lý vấn đề hệ thống, tranh chấp, đối soát và thực hiện hoàn tiền theo thẩm quyền; nhân viên trạm không có quyền chuyển tiền.

## Căn cứ nghiệp vụ
- ChargeOps Vault: Booking/README.
- ChargeOps Vault: Booking/01-backend-design.
- ChargeOps Vault: Booking/06-bkg-002-system-config-and-booking-policy.$document$,
'4.9.0',
'vi'
);

-- Source: docs/ChargeOps/Booking/08-legal-documents-ssot-and-pragmatic-search.md
-- Source: docs/ChargeOps/Booking/01-backend-design.md
INSERT INTO chargeops_legal_seed VALUES (
'privacy-policy',
'PRIVACY_POLICY',
'ALL',
'Chính sách bảo mật dữ liệu',
'ChargeOps Privacy & Data Security',
'Mô tả dữ liệu phục vụ tài khoản, đặt chỗ, check-in và hỗ trợ; mục đích sử dụng và phạm vi truy cập theo quyền trên ChargeOps.',
$document$# Chính sách bảo mật dữ liệu ChargeOps

> Phạm vi: Diễn giải nghiệp vụ theo ChargeOps Vault, đối chiếu 08/09/2026. Các luồng Booking–Payment được mô tả theo thiết kế v4.9; tài liệu không xác nhận mọi chức năng đã triển khai. Quyền thao tác và tham số từng giao dịch căn cứ dữ liệu hệ thống trả về.


Chính sách bảo mật này quy định cách thức ChargeOps thu thập, lưu trữ, xử lý và bảo vệ dữ liệu cá nhân cùng lịch sử giao dịch của người dùng khi sử dụng dịch vụ của chúng tôi.

---

### 1. Dữ liệu tài khoản
1. Thông tin tài khoản gồm họ tên, email, số điện thoại và quyền truy cập được sử dụng để đăng nhập, liên lạc và vận hành dịch vụ.
2. Người dùng cần bảo mật thông tin đăng nhập và báo cho bộ phận hỗ trợ khi nghi ngờ tài khoản bị truy cập trái phép.

---

### 2. Mục đích sử dụng dữ liệu giao dịch
1. Thông tin đặt chỗ, giá gói tại thời điểm đặt, trạng thái thanh toán, hoàn tiền và lịch sử check-in được sử dụng để cung cấp dịch vụ, đối soát và giải quyết khiếu nại.
2. Nội dung phiếu hỗ trợ và kết luận sự cố được sử dụng để xác minh vấn đề và xác định cách xử lý phù hợp. Dữ liệu thanh toán mô phỏng phục vụ kiểm thử và trình diễn, không chứng minh có giao dịch ngân hàng thật.

---

### 3. Phạm vi truy cập dữ liệu
1. Tài xế truy cập đặt chỗ và phiếu hỗ trợ của mình. Chủ trạm truy cập dữ liệu thuộc trạm và thông tin tài chính của mình theo quyền được cấp.
2. Nhân viên có phân công còn hiệu lực chỉ truy cập dữ liệu vận hành và phiếu hỗ trợ thuộc trạm được giao; quyền này không bao gồm số tiền, doanh thu, thông tin thanh toán hoặc tài khoản ngân hàng.
3. Quản trị viên truy cập dữ liệu theo chức năng quản trị, hỗ trợ, giải quyết tranh chấp và đối soát. Quyền truy cập được kiểm tra tại máy chủ, không chỉ giới hạn bằng giao diện.

---

### 4. Vị trí và quét mã QR
1. Chức năng tìm trạm gần vị trí sử dụng tọa độ được cung cấp cho yêu cầu tìm kiếm. Mã QR được sử dụng để xác định cổng sạc và kiểm tra điều kiện check-in.
2. Người dùng quản lý quyền vị trí và camera trong cài đặt thiết bị. Các chức năng cần quyền tương ứng có thể không sử dụng được khi quyền bị từ chối.

---

### 5. Tra cứu và hỗ trợ về dữ liệu
1. Người dùng có thể tra cứu dữ liệu trong phạm vi tài khoản và gửi yêu cầu hỗ trợ khi thông tin không chính xác hoặc có dấu hiệu bị truy cập trái phép.
2. Yêu cầu liên quan đến chỉnh sửa hoặc xóa dữ liệu cần được xem xét cùng lịch sử giao dịch và các nghĩa vụ đang được xử lý; việc gửi yêu cầu không đồng nghĩa toàn bộ lịch sử đã được xóa.

## Căn cứ nghiệp vụ
- ChargeOps Vault: Booking/08-legal-documents-ssot-and-pragmatic-search.
- ChargeOps Vault: Booking/01-backend-design.$document$,
'4.9.0',
'vi'
);

-- Source: docs/ChargeOps/License/license-station-eligibility-policy.vi.md
-- Source: docs/ChargeOps/License/license-admin-backend-api-design.md
INSERT INTO chargeops_legal_seed VALUES (
'station-owner-license-agreement',
'LICENSE_AGREEMENT',
'OWNER',
'Thỏa thuận cấp phép & Vận hành trạm sạc',
'Station Owner License Agreement',
'Thỏa thuận thuê bao phần mềm theo từng trạm, phí license, trách nhiệm vận hành và điều kiện nhận đặt chỗ mới trên ChargeOps.',
$document$# Thỏa thuận cấp phép & Vận hành trạm sạc (B2B)

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
1. Chủ trạm phân công tài khoản nhân viên vận hành (Staff) vào từng trạm để hỗ trợ giám sát và xử lý sự cố. Quyền vận hành chỉ có hiệu lực khi phân công còn **ACTIVE**; Staff không phải vai trò tài khoản độc lập.
2. Nhân viên chỉ được phân quyền thao tác trong phạm vi các trạm sạc được chỉ định và không có quyền can thiệp vào cấu hình gói license hay thông tin tài chính của Chủ trạm.

## Căn cứ nghiệp vụ
- ChargeOps Vault: License/license-station-eligibility-policy.vi.
- ChargeOps Vault: License/license-admin-backend-api-design.$document$,
'4.9.0',
'vi'
);

-- Source: docs/ChargeOps/Booking/README.md
-- Source: docs/ChargeOps/Booking/01-backend-design.md
-- Source: docs/ChargeOps/Connector/chargepoint-connector-invariants-t17.md
INSERT INTO chargeops_legal_seed VALUES (
'operational-regulations',
'OPERATIONAL_REGULATION',
'ALL',
'Quy chế vận hành nền tảng sạc điện',
'ChargeOps Operational Regulations',
'Quy chế vận hành giao dịch sạc điện, cơ chế xử lý tranh chấp, quy định vắng mặt (no-show) và khóa cổng sạc đồng thời.',
$document$# Quy chế vận hành nền tảng sạc điện ChargeOps

> Phạm vi: Diễn giải nghiệp vụ theo ChargeOps Vault, đối chiếu 08/09/2026. Các luồng Booking–Payment được mô tả theo thiết kế v4.9; tài liệu không xác nhận mọi chức năng đã triển khai. Quyền thao tác và tham số từng giao dịch căn cứ dữ liệu hệ thống trả về.


Quy chế này quy định các nguyên tắc vận hành của ChargeOps, áp dụng cho tài xế, chủ trạm, nhân viên được phân công và quản trị viên trong phạm vi quyền tương ứng.

---

### 1. Cơ chế khóa tài nguyên đồng thời (Concurrency Locking)
1. Để đảm bảo không xảy ra tình trạng đặt trùng lặp (Double Booking) trên cùng một súng sạc, hệ thống áp dụng cơ chế khóa bi quan (**Pessimistic Locking**) ở tầng cơ sở dữ liệu khi xử lý yêu cầu tạo booking.
2. Các yêu cầu giữ chỗ trên **cùng một cổng sạc** được kiểm tra lịch dưới khóa để tránh chồng lấn; đây không phải cơ chế tuần tự hóa mọi giao dịch trên toàn nền tảng.

---

### 2. Quy trình giữ chỗ 10 phút chờ thanh toán
1. Ngay khi tài xế gửi yêu cầu đặt chỗ thành công, hệ thống lập tức khóa khung giờ đó và đặt booking ở trạng thái **PENDING**.
2. Tài xế có tối đa **10 phút** để thực hiện thanh toán. Một đồng hồ đếm ngược được kích hoạt từ thời điểm tạo.
3. Khi đến hạn 10 phút mà hệ thống chưa xác nhận thanh toán hợp lệ, đặt chỗ hết hạn (**EXPIRED**) và không còn giữ khung giờ. Thanh toán đến muộn được đối soát riêng. Thời hạn này khác với ân hạn hủy tính từ lúc xác nhận thanh toán.

---

### 3. Cửa sổ Check-in & Quy định tài xế vắng mặt (No-show)
1. Tài xế phải hoàn tất xác nhận check-in bằng mã QR hợp lệ của đúng cổng sạc từ giờ bắt đầu đã đặt đến **trước thời điểm kết thúc đã đặt trừ 15 phút**. Quét mã hoặc xem trước thông tin chưa được coi là check-in thành công.
2. Tại hoặc sau hạn chót, đặt chỗ **CONFIRMED** chưa check-in được ghi nhận **CANCELLED** với lý do **NO_SHOW**, không phải trạng thái hết hạn thanh toán **EXPIRED**. Đến muộn không kéo dài phiên; kết thúc sớm không làm thay đổi giá gói đã mua.
3. Đặt chỗ no-show không còn giữ khung giờ, nhưng việc cổng sạc nhận đặt chỗ khác vẫn phụ thuộc điều kiện thiết bị và thời gian đặt trước. No-show hoàn **0%**, trừ trường hợp sự cố trạm được xác nhận đủ điều kiện hoàn tiền.

---

### 4. Nguyên tắc bảo toàn giá gói tại thời điểm đặt
1. Giá gói thời gian được hệ thống tính lại khi tạo đặt chỗ và lưu cùng chi tiết giá áp dụng tại thời điểm đó. Biểu giá theo kWh, công suất cổng sạc và điện năng ước tính là căn cứ tính giá gói, không phải hóa đơn theo điện năng thực đo. Không thu thêm phí dịch vụ hoặc hoa hồng booking.
2. Mọi sự thay đổi về biểu giá giờ cao điểm/thấp điểm (TOU Rates) hoặc giá điện của trạm sạc sau thời điểm đặt chỗ sẽ **chỉ áp dụng cho các booking mới** và tuyệt đối không làm thay đổi số tiền của các booking đã tạo trước đó.

---

### 5. Tiếp nhận & Xử lý sự cố kỹ thuật
1. Khi phát hiện sự cố, chủ trạm hoặc nhân viên được phân công ghi nhận và xử lý theo quyền vận hành: cổng sạc không phục vụ được dùng trạng thái **OFFLINE**; trụ sạc có trạng thái vận hành **OFFLINE** hoặc **MAINTENANCE** tùy tình huống. Bảo trì thông thường phải kiểm tra các đặt chỗ đã bán; sự cố khẩn cấp cần được ghi nhận riêng kèm lý do và các đặt chỗ bị ảnh hưởng.
2. Sự cố trạm được xác nhận đủ điều kiện hoàn **100% giá gói** trong MVP, kể cả giữa phiên; không hoàn trùng phần đã hoàn. Việc lập hoặc đóng phiếu hỗ trợ không tự thực hiện chuyển tiền. Quản trị viên xử lý hoàn tiền và đối soát theo thẩm quyền; chủ trạm và nhân viên cung cấp thông tin, xác minh sự cố trong phạm vi được giao.

## Căn cứ nghiệp vụ
- ChargeOps Vault: Booking/README.
- ChargeOps Vault: Booking/01-backend-design.
- ChargeOps Vault: Connector/chargepoint-connector-invariants-t17.$document$,
'4.9.0',
'vi'
);

-- Source: docs/ChargeOps/Booking/README.md | Invariants; D-LIMIT
-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 2.1, 4, 5
-- Source: docs/ChargeOps/Booking/06-bkg-002-system-config-and-booking-policy.md | Section 2.1
INSERT INTO chargeops_legal_seed VALUES (
'booking-time-and-availability','OPERATIONAL_REGULATION','ALL',
'Quy định thời gian đặt chỗ và lịch khả dụng','ChargeOps · Đặt chỗ',
'Ngày bắt đầu hôm nay/ngày mai, đặt trước 60 phút, bước 30 phút, phiên qua ngày và phân biệt xem lịch với giữ chỗ.',
$document$# Quy định thời gian đặt chỗ và lịch khả dụng

> Phạm vi: Quy tắc theo thiết kế Booking v4.9, đối chiếu Vault ngày 08/09/2026. Tham số áp dụng cho giao dịch cụ thể do hệ thống trả về; tài liệu không xác nhận toàn bộ luồng đã được triển khai.

## 1. Chọn ngày, giờ và thời lượng
Theo mặc định v4.9, tài xế chọn ngày bắt đầu là hôm nay hoặc ngày mai theo thời gian nghiệp vụ của hệ thống. Đây không phải quyền chọn bất kỳ thời điểm nào trong 48 giờ tiếp theo. Phiên bắt đầu ngày mai vẫn có thể kết thúc vào ngày kế tiếp nếu giờ hoạt động cho phép.

Giờ bắt đầu phải cách thời điểm hệ thống xử lý ít nhất 60 phút và nằm trên lưới 30 phút. Ví dụ hiện tại 09:10 thì cận bắt đầu là 10:30; cận này chưa bảo đảm có cổng trống. Thời lượng có sàn 30 phút, tăng theo bước 30 phút. Giới hạn cụ thể phải lấy từ hệ thống; không coi mức tối đa 180 phút đang có trong code là một chính sách đã chốt vĩnh viễn.

## 2. Giờ hoạt động và phiên qua ngày
Toàn bộ khoảng sạc phải nằm trong các ca phục vụ hợp lệ. Chỉ kiểm tra đầu và cuối phiên không đủ nếu giữa phiên có khoảng trạm đóng cửa. Phiên qua đêm phải kiểm tra lịch tất cả các ngày liên quan.

Hai phiên được tiếp giáp ở giờ kết thúc/bắt đầu mà không bị xem là chồng lấn. Không tự cộng khoảng đệm 10 phút vào lịch; thời hạn giữ chỗ thanh toán 10 phút có ý nghĩa khác.

## 3. Xem lịch chưa phải giữ chỗ
Số cổng đang khả dụng và trạng thái đang mở trên trang tìm kiếm phản ánh lúc tra cứu, không bảo đảm còn lịch trống vào giờ tài xế muốn đặt. Xem giá không giữ cổng hoặc giữ giá; chỉ PENDING được tạo thành công mới giữ khung giờ trong hạn thanh toán.

Khi tạo đặt chỗ, máy chủ kiểm tra lại điều kiện, lịch và giá. Hai người không được giữ cùng cổng trong khoảng chồng lấn. Một tài xế đặt các cổng khác nhau trùng giờ có thể nhận cảnh báo; không suy ra lệnh cấm chung khi chưa có chính sách tương ứng.

## 4. Đến muộn và thay đổi cấu hình
Đến muộn không kéo dài phiên hoặc lùi giờ kết thúc. Thay đổi cấu hình hiện hành không được tự sửa các mốc đã lưu của giao dịch cũ. Đặt lại sau khi hết hạn giữ chỗ sử dụng dữ liệu tại lần đặt mới.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/README: Những điều không được thay đổi ngầm; D-LIMIT.
- Booking/01-backend-design: mục 2.1, 4 và 5, xem giá, xử lý thời gian và phiên qua ngày.
- Booking/06-bkg-002-system-config-and-booking-policy: mục 2.1, tham số mặc định.$document$,
'4.9.0','vi');

-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 2, 2.1, 2.3
-- Source: docs/ChargeOps/Booking/README.md | Invariants; D-PRICE
INSERT INTO chargeops_legal_seed VALUES (
'time-package-pricing-policy','OPERATIONAL_REGULATION','ALL',
'Chính sách giá gói thời gian và bảo toàn giá đặt chỗ','ChargeOps · Giá gói',
'Giá TOU, công suất và điện năng ước tính; giới hạn của hệ số 0,62; xác nhận lại khi giá thay đổi và giữ giá đã mua.',
$document$# Chính sách giá gói thời gian và bảo toàn giá đặt chỗ

> Phạm vi: Mô hình giá gói trong thiết kế Booking v4.9. Đây không phải chứng nhận điện năng thực đo hoặc xác nhận mọi API tính giá đã triển khai.

## 1. Sản phẩm tài xế mua
Tài xế mua gói sử dụng cổng sạc theo thời gian và trả đủ trước khi sử dụng. Đây không phải tiền cọc hoặc giao dịch quyết toán theo kWh thực đo. ChargeOps không thu phí dịch vụ hay hoa hồng booking; phí thuê bao license của chủ trạm là khoản riêng.

Chủ trạm thiết lập biểu giá theo kWh và khung giờ. Giá TOU được chọn theo khoảng giờ sạc, không theo lúc mở màn hình. Một gói đi qua nhiều khung giá được tách thành các dòng tương ứng.

## 2. Công thức của mô hình v4.9
Mỗi dòng lấy công suất cổng nhân số giờ sử dụng và hệ số 0,62 để tính điện năng ước tính, làm tròn HALF_UP đến một chữ số thập phân. Điện năng đã làm tròn được nhân với đơn giá theo kWh rồi làm tròn HALF_UP đến 1.000 đồng. Tổng gói bằng tổng tiền từng dòng, không làm tròn tổng thêm lần nữa.

Ví dụ một dòng 60 kW, 60 phút, đơn giá 3.400 đồng/kWh: điện năng ước tính 37,2 kWh; tiền trước làm tròn 126.480 đồng; giá dòng 126.000 đồng. Đây là ví dụ tính toán, không phải biểu giá chung của mọi trạm.

## 3. Giới hạn của số liệu ước tính
Hệ số 0,62 là giả định của prototype, chưa được hiệu chỉnh bằng dữ liệu sạc thực tế. Nó không phải hiệu suất được kiểm định, mức pin hoặc cam kết giao đủ lượng điện tính ra. Công suất danh định không chứng minh công suất xe thực nhận trong suốt phiên.

## 4. Xác nhận giá và lưu giao dịch
Giá trên màn chọn giờ là dự tính. Máy chủ tính lại khi tiếp tục đặt và khi tạo đặt chỗ. Nếu số tiền, nội dung giá hoặc chính sách khác thông tin đã đồng ý, tài xế phải xem và xác nhận lại; không tự tạo đặt chỗ theo giá mới.

Sau khi tạo PENDING thành công, giá gói cùng căn cứ tính được lưu cho giao dịch. Chủ trạm thay biểu giá không sửa giá booking đã tạo. Nếu giữ chỗ hết hạn, đặt lại dùng dữ liệu hiện hành. Kết thúc sớm không tự làm giảm giá gói đã mua.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/01-backend-design: mục 2, 2.1 và 2.3, công thức, xác nhận lại giá và nguồn gốc hệ số 0,62.
- Booking/README: nguyên tắc gói thời gian trả trước, không phí booking; quyết định D-PRICE.$document$,
'4.9.0','vi');

-- Source: docs/ChargeOps/Booking/README.md | Invariants
-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 2.2, 4, 6
-- Source: docs/ChargeOps/Booking/06-bkg-002-system-config-and-booking-policy.md | Section 1.3
-- Source: docs/ChargeOps/Booking/07-policy-ssot-and-dynamic-presentation.md | Section 2
INSERT INTO chargeops_legal_seed VALUES (
'cancellation-and-refund-policy','OPERATIONAL_REGULATION','ALL',
'Chính sách hủy đặt chỗ và hoàn tiền','ChargeOps · Hủy và hoàn',
'Điều kiện hoàn 100%/0%, ranh giới ân hạn, xác nhận lại khoản hoàn và ngoại lệ sự cố trạm.',
$document$# Chính sách hủy đặt chỗ và hoàn tiền

> Phạm vi: Chính sách Booking v4.9. Quyền hủy, khoản hoàn và deadline cụ thể căn cứ dữ liệu giao dịch do hệ thống trả về; quy trình chuyển tiền trong MVP được thiết kế dưới dạng mô phỏng.

## 1. Hai thời hạn độc lập
Giữ chỗ thanh toán mặc định là 10 phút từ lúc tạo PENDING. Ân hạn hủy mặc định là 10 phút từ lần đầu hệ thống xác nhận thanh toán hợp lệ. Thông báo lặp hoặc thanh toán lại không mở một ân hạn mới.

Hạn áp dụng được lưu cho từng giao dịch. Thay đổi cấu hình về sau không tính lại deadline booking cũ hoặc khôi phục quyền hoàn đã hết hạn. Thời điểm xử lý tại máy chủ là căn cứ quyết định, không phải lúc chạm nút trên thiết bị.

## 2. Hủy do thay đổi nhu cầu
Booking CONFIRMED, chưa check-in, được hoàn 100% giá gói còn đủ điều kiện hoàn khi xử lý hủy trước cả hạn ân hạn và giờ bắt đầu. Tại đúng một trong hai mốc, điều kiện hoàn do đổi ý không còn đáp ứng.

Ví dụ xác nhận thanh toán lúc 09:00, giờ sạc 11:00: xử lý hủy trước 09:10 còn trong ân hạn, đúng 09:10 hoàn 0% do đổi ý. Nếu giờ bắt đầu hoặc deadline đã lưu đến sớm hơn thì áp dụng mốc sớm hơn.

Sau ân hạn vẫn được hủy khi booking còn CONFIRMED, nhưng hoàn 0%. Không dùng quy tắc cũ chia tầng 100%/50%/0% theo khoảng cách đến giờ sạc. Hủy PENDING chưa có tiền gói được áp dụng không phát sinh hoàn giá gói.

## 3. Xem trước và xác nhận
Số tiền xem trước có thể đổi khi đến hạn hoặc có nghĩa vụ hoàn khác. Nếu lúc xác nhận không còn khớp số tiền đã đồng ý, thao tác hủy phải dừng để tài xế xác nhận lại. Không âm thầm hủy với khoản hoàn thấp hơn.

## 4. Sự cố trạm và vắng mặt
No-show thông thường hoàn 0%. Sự cố trạm được xác nhận đủ điều kiện hoàn 100% giá gói trong MVP, kể cả giữa phiên hoặc kết luận lỗi sau no-show. Phần đã hoàn hoặc đang giữ nghĩa vụ không được tạo hoàn trùng. Tiền thanh toán dư được đối soát riêng.

## 5. Theo dõi kết quả
Hủy booking, xác nhận quyền được hoàn và chuyển tiền hoàn là các bước riêng. Đóng phiếu hỗ trợ không chứng minh đã nhận tiền. Người dùng theo dõi trạng thái khoản hoàn; kết quả chuyển tiền trong demo là mô phỏng.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/README: hai đồng hồ 10 phút, hoàn 100%/0%, lỗi trạm.
- Booking/01-backend-design: mục 2.2, 4 và 6, quyết định hủy và nghĩa vụ hoàn.
- Booking/06-bkg-002-system-config-and-booking-policy: mục 1.3, bảo toàn deadline.
- Booking/07-policy-ssot-and-dynamic-presentation: mục 2, dữ liệu động và quyết định cuối tại backend.$document$,
'4.9.0','vi');
-- Source: docs/ChargeOps/Booking/README.md | Check-in invariant
-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 4, 5, 7
-- Source: docs/ChargeOps/Booking/02-api-contract.md | QR flow; capabilities and errors
INSERT INTO chargeops_legal_seed VALUES (
'qr-check-in-and-no-show-policy','OPERATIONAL_REGULATION','ALL',
'Quy định check-in QR, đến muộn và vắng mặt','ChargeOps · Check-in',
'Quét đúng cổng, xác nhận QR còn hiệu lực, check-in trước giờ kết thúc trừ 15 phút và no-show CANCELLED/NO_SHOW.',
$document$# Quy định check-in QR, đến muộn và vắng mặt

> Phạm vi: Quy trình được quy định trong thiết kế Booking v4.9; không xác nhận mọi bước đã được triển khai. Mốc giờ và quyền thao tác cụ thể do máy chủ trả về.

## 1. Điều kiện nhận chỗ
Tài xế dùng booking của mình đã được xác nhận thanh toán, tại đúng cổng đã đặt, trong cửa sổ check-in và khi thiết bị còn phục vụ được. Ảnh QR hoặc mã định danh cổng không tự chứng minh người quét có quyền sử dụng phiên.

Quét mã chỉ dẫn đến kiểm tra và xem trước. Tài xế phải hoàn tất xác nhận và nhận kết quả thành công. Mã xác nhận có thời hạn; mã hết hạn, đã sử dụng hoặc sai cổng cần quét lại theo hướng dẫn. Khi không kiểm tra được mã, không coi check-in đã thành công.

## 2. Cửa sổ thời gian
Check-in mở từ giờ bắt đầu đã đặt đến trước giờ kết thúc đã đặt trừ 15 phút. Với phiên 14:00–15:00, cửa sổ hợp lệ là từ 14:00 đến trước 14:45; đúng 14:45 không còn check-in được.

Mở màn hình sớm hoặc gửi yêu cầu trước hạn không bảo đảm thành công nếu đến lúc máy chủ xử lý đã hết hạn. Không dùng đồng hồ thiết bị để tự mở lại quyền check-in.

## 3. Đến muộn và kết thúc sớm
Đến muộn trong cửa sổ cho phép không làm lùi giờ kết thúc hoặc cộng thời lượng. Kết thúc sớm không tự giảm giá gói. Khung giờ được nhả không có nghĩa cổng lập tức bán lại được: còn xét thiết bị, phiên khác và quy tắc đặt trước.

## 4. Vắng mặt
Booking CONFIRMED chưa check-in tại hoặc sau hạn chót được ghi nhận CANCELLED với lý do NO_SHOW. EXPIRED dành cho hết hạn giữ chỗ thanh toán, không phải no-show trong v4.9. Vắng mặt thông thường hoàn 0%; lỗi trạm xác nhận có quy trình hoàn riêng.

## 5. License thay đổi và thử lại
License mất hiệu lực sau khi booking đã được nhận hợp lệ không tự tước quyền nhận chỗ đã mua. Trạm thực sự không phục vụ được vẫn phải xử lý sự cố. Nếu mất mạng sau xác nhận, cần tra cứu hoặc thử lại cùng thao tác để biết kết quả, không tự tạo phiên mới.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/README: cửa sổ check-in và no-show.
- Booking/01-backend-design: mục 4, 5 và 7, xác nhận QR, đến muộn, bảo toàn giao dịch.
- Booking/02-api-contract: luồng QR, quyền thao tác, mã hết hạn và thử lại.$document$,
'4.9.0','vi');

-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 2, 4, 6
-- Source: docs/ChargeOps/Booking/02-api-contract.md | Payment flow and scenarios
INSERT INTO chargeops_legal_seed VALUES (
'payment-reconciliation-policy','OPERATIONAL_REGULATION','ALL',
'Quy định thanh toán và đối soát giao dịch','ChargeOps · Thanh toán',
'Thanh toán mô phỏng, xác nhận trong hạn giữ chỗ, tiền thiếu/thừa/đến muộn và phân biệt thông báo lặp với chuyển tiền hai lần.',
$document$# Quy định thanh toán và đối soát giao dịch

> Phạm vi: Thiết kế thanh toán MVP v4.9 dùng mô phỏng, không phải bằng chứng kết nối ngân hàng thật hoặc xác nhận toàn bộ luồng đã được triển khai.

## 1. Khoản thanh toán của gói
Mỗi booking có một khoản thanh toán logic tương ứng giá gói. Những lần ghi nhận tiền vào được theo dõi riêng để đối soát. Có tên nhà cung cấp trong danh mục kỹ thuật không chứng minh đã tích hợp cổng thanh toán đó. Mô hình không cung cấp chức năng nạp/rút ví cho tài xế.

## 2. Điều kiện xác nhận
Hệ thống xác nhận cho booking còn PENDING, chưa đến hạn giữ chỗ, với số tiền, loại tiền và tham chiếu hợp lệ. Trạng thái thanh toán và booking phải được ghi nhận nhất quán.

Thời gian trên thông báo bên ngoài không được dùng để lùi đồng hồ, khôi phục chỗ đã hết hạn hoặc lấy lại cổng đã nhả. EXPIRED hoặc CANCELLED không tự hồi sinh khi có thông báo tiền vào mới.

## 3. Khoản tiền không khớp
Trong MVP, nhiều khoản chuyển thiếu không tự cộng dồn để xác nhận gói. Chuyển thừa cũng không tự xác nhận bằng một phần tiền khi chưa có quy trình phân bổ tương ứng. Khoản thiếu, thừa, không xác định được booking hoặc đến muộn được ghi nhận để đối soát và xử lý hoàn phù hợp.

Tài xế theo dõi kết quả đối soát; màn hình thông báo đã chuyển tiền không thay thế trạng thái xác nhận giữ chỗ tại ChargeOps.

## 4. Thông báo lặp và tiền vào hai lần
Thông báo lặp cùng một giao dịch không được ghi thành hai lần thu. Hai lần tiền vào có tham chiếu khác nhau phải được lưu như hai khoản cần đối soát.

Ví dụ giá gói 120.000 đồng, một khoản đúng đã được áp dụng rồi nhận thêm 120.000 đồng: tổng nhận 240.000 đồng, giá gói vẫn 120.000 đồng, phần dư 120.000 đồng xử lý riêng. Hoàn phần dư không đồng nghĩa gói đã mua bị hoàn hoặc hủy.

## 5. Kết quả chuyển hoàn chưa rõ
Khi chưa xác định một lần chuyển hoàn thành công hay thất bại, không tự phát lệnh mới chỉ vì hết thời gian chờ. Quản trị viên đối soát kết quả cũ trước để tránh hoàn trùng. Đang xử lý hoặc chưa rõ kết quả khác thất bại đã xác minh; nghĩa vụ hoàn đang giữ không được coi là tiền sẵn sàng chi cho chủ trạm.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/01-backend-design: mục 2, 4 và 6, simulator, xác nhận thanh toán và phân bổ receipt/refund.
- Booking/02-api-contract: luồng thanh toán và kịch bản lỗi, thử lại.$document$,
'4.9.0','vi');

-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 2, 6, 7
-- Source: docs/ChargeOps/Booking/README.md | Station failure invariant; MVP scope
INSERT INTO chargeops_legal_seed VALUES (
'station-incident-and-support-policy','OPERATIONAL_REGULATION','ALL',
'Quy trình hỗ trợ sự cố trạm và giải quyết tranh chấp','ChargeOps · Hỗ trợ sự cố',
'Tiếp nhận ticket, phạm vi xác minh Owner/Staff/Admin, hoàn lỗi trạm và ảnh hưởng đến khoản chi trả cho chủ trạm.',
$document$# Quy trình hỗ trợ sự cố trạm và giải quyết tranh chấp

> Phạm vi: Quy trình ticket và hoàn lỗi trạm theo thiết kế MVP v4.9; tài liệu không xác nhận mọi chức năng đã được triển khai.

## 1. Tiếp nhận vấn đề
Tài xế gửi phiếu hỗ trợ cho booking hoặc vấn đề của mình, cung cấp diễn biến và thông tin cần xác minh. Chủ trạm và nhân viên được phân công còn hiệu lực xử lý vận hành trong phạm vi trạm; quản trị viên xử lý vấn đề hệ thống, tranh chấp và đối soát theo quyền.

Ticket MVP gồm tạo, đọc, trả lời, theo dõi trạng thái và kết luận. Phạm vi này không cam kết thời gian xử lý cố định, trò chuyện thời gian thực hay đính kèm nếu chưa được triển khai.

## 2. Xác minh và kết luận
Mở phiếu chưa tự chứng minh trạm có lỗi. Nội dung trao đổi, căn cứ, người kết luận và thời điểm được giữ để đối soát. Không quyết định tiền hoàn chỉ dựa vào việc phiếu đã đóng.

Sự cố khiến trạm không phục vụ được cần ghi nhận riêng với lý do và booking bị ảnh hưởng. Không dùng bảo trì thông thường để bỏ qua ràng buộc bảo vệ booking đã bán.

## 3. Hoàn tiền do lỗi trạm
Sự cố trạm được xác nhận đủ điều kiện hoàn 100% giá gói trong MVP, kể cả giữa phiên. Nếu kết luận sau no-show, việc đã ghi nhận vắng mặt không tự phủ nhận quyền hoàn do lỗi trạm. Không hoàn trùng phần đã trả hoặc đang giữ nghĩa vụ.

Chủ trạm và nhân viên hỗ trợ xác minh, không tự ghi đã chuyển tiền hoàn. Xác nhận quyền hoàn và thực hiện chuyển tiền là hai bước có thẩm quyền; trong demo chuyển tiền là mô phỏng.

## 4. Ảnh hưởng đến khoản chi trả
Ticket sự cố sạc chưa kết luận có thể giữ phần tiền chưa chi của booking chờ giải quyết. Ticket loại khác không tự khóa toàn bộ doanh thu. Khi kết luận không phải lỗi trạm hoặc tranh chấp được xử lý hợp lệ, khoản giữ được giải quyết theo quy trình.

Nếu tiền đã chi cho chủ trạm trước khi xác nhận lỗi, vẫn ghi nghĩa vụ hoàn cho tài xế và khoản điều chỉnh phía chủ trạm. Không xóa lịch sử chi thành công hoặc chờ chủ trạm trả lại mới ghi nhận quyền hoàn của tài xế.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/01-backend-design: mục 2, 6 và 7, ticket MVP, giữ tiền, hoàn và phân quyền.
- Booking/README: nguyên tắc lỗi trạm hoàn đủ giá gói, ticket tách khỏi chuyển tiền.$document$,
'4.9.0','vi');
-- Source: docs/ChargeOps/License/license-station-eligibility-policy.vi.md | Final policy sections 4-7
-- Source: docs/ChargeOps/Discovery/driver-station-discovery-flow.vi.md | FR02/FR03/FR04
-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 5, 7
INSERT INTO chargeops_legal_seed VALUES (
'station-discovery-and-eligibility-policy','OPERATIONAL_REGULATION','ALL',
'Điều kiện hiển thị trạm và tiếp nhận đặt chỗ','ChargeOps · Điều kiện trạm',
'Tách trạng thái trạm, license, trụ và cổng; điều kiện tìm kiếm/booking; bảo toàn giao dịch đã được nhận hợp lệ.',
$document$# Điều kiện hiển thị trạm và tiếp nhận đặt chỗ

> Phạm vi: Chính sách Station–License và thiết kế discovery/booking trong Vault. Phần đặt trước theo v4.9 không phải xác nhận toàn bộ hành vi đã triển khai.

## 1. Các điều kiện độc lập
Trạng thái trạm thể hiện phê duyệt và vận hành trên nền tảng. License thể hiện quyền nhận nghiệp vụ mới theo thuê bao. Trạng thái cấp thiết bị của trụ và trạng thái sử dụng của cổng phản ánh những vấn đề khác. Không tự đồng bộ tất cả thành một trạng thái chung.

Ví dụ trạm ACTIVE, license SUSPENDED, trụ đã kích hoạt, cổng AVAILABLE vẫn là tổ hợp dữ liệu có thể tồn tại. Cổng sẵn sàng về phần cứng không làm license trở lại hiệu lực.

## 2. Xuất hiện trong tìm kiếm
Trạm phải ACTIVE, có license ACTIVE trong khoảng từ ngày bắt đầu đến trước thời điểm hết hạn, có trụ đã kích hoạt và cổng AVAILABLE. License còn ghi ACTIVE nhưng đã qua expiresAt không đủ điều kiện, kể cả tác vụ cập nhật trạng thái chạy chậm.

Đang mở, khoảng cách, số cổng sẵn sàng và giá từ trên thẻ trạm là thông tin tra cứu. Giá từ theo kWh không phải tổng giá gói cụ thể; số cổng sẵn sàng không bảo đảm lịch trống trong giờ khách chọn.

## 3. Kiểm tra khi đặt chỗ
Tạo booking còn kiểm tra cổng cụ thể, toàn bộ giờ hoạt động, biểu giá và khoảng trống. Thấy trạm trên màn hình không thay thế kiểm tra máy chủ. Yêu cầu có thể bị từ chối nếu điều kiện thay đổi sau tra cứu.

Thiết kế v4.9 phân biệt cổng IN_USE hiện tại với lịch tương lai: không suy ra cổng bận lúc này thì mọi giờ sau đều không được đặt. Cổng không phục vụ được hoặc trụ bảo trì vẫn cần chặn theo quy tắc thiết bị.

## 4. License mất hiệu lực
Trạm không nhận booking mới và không xuất hiện trong discovery khi license không còn dùng được. Owner/Admin vẫn quản lý dữ liệu theo quyền. Không tự hủy booking đã xác nhận hoặc cắt phiên đang chạy chỉ vì license thay đổi.

PENDING được nhận hợp lệ có thể hoàn tất thanh toán trong hạn nếu trạm còn phục vụ được. Bảo toàn giao dịch do license thay đổi không có nghĩa bỏ qua sự cố thực tế, đình chỉ vì an toàn hoặc quyết định vận hành riêng.

## Căn cứ nghiệp vụ
- ChargeOps Vault — License/license-station-eligibility-policy.vi: phần policy cuối, mục 4–7.
- Discovery/driver-station-discovery-flow.vi: tìm kiếm, chi tiết và lịch khả dụng FR02/FR03/FR04.
- Booking/01-backend-design: mục 5 và 7, lịch tương lai và giao dịch đã được nhận.$document$,
'4.9.0','vi');

-- Source: docs/ChargeOps/License/license-admin-backend-api-design.md | Glossary; sections 7.4-7.7
-- Source: docs/ChargeOps/License/license-station-eligibility-policy.vi.md | Existing bookings
INSERT INTO chargeops_legal_seed VALUES (
'license-lifecycle-and-renewal-policy','LICENSE_AGREEMENT','OWNER',
'Quy định hiệu lực, gia hạn và đình chỉ thuê bao trạm','ChargeOps · Vòng đời license',
'Kỳ tháng/năm lịch, gia hạn tạo kỳ mới, đình chỉ không dừng đồng hồ và phân biệt hủy kỳ với chấm dứt quan hệ cấp phép.',
$document$# Quy định hiệu lực, gia hạn và đình chỉ thuê bao trạm

> Phạm vi: Chính sách lifecycle trong Vault. Phần kiểm soát cấp phép xuyên kỳ được nguồn mô tả là template thiết kế; không coi template là chức năng đã triển khai đầy đủ.

## 1. Ý nghĩa và thời hạn
License là quyền sử dụng phần mềm theo từng trạm, không phải giấy phép kinh doanh của cơ quan nhà nước. Thanh toán B2B ngoài nền tảng tách khỏi giao dịch tài xế.

Kỳ tháng được tính bằng một tháng lịch, kỳ năm bằng một năm lịch theo múi giờ Asia/Ho_Chi_Minh, không quy đổi cứng thành 30 hoặc 365 ngày. Khoảng hiệu lực bao gồm startAt, không bao gồm expiresAt. Đúng thời điểm hết hạn thì kỳ không còn quyền nhận nghiệp vụ mới.

## 2. Trạng thái và kích hoạt lại
PENDING là kỳ đã tạo nhưng chưa có hiệu lực. ACTIVE chỉ dùng được trong khoảng hiệu lực. SUSPENDED là đình chỉ tạm thời và đồng hồ thời hạn vẫn chạy. CANCELLED và EXPIRED là trạng thái kết thúc của kỳ, không được bật lại thành ACTIVE.

Kích hoạt lại áp dụng cho kỳ SUSPENDED còn thời hạn và đáp ứng điều kiện gỡ đình chỉ, khác với gia hạn.

## 3. Gia hạn tạo kỳ mới
Gia hạn không kéo dài hoặc ghi đè kỳ cũ. Kỳ mới có mã riêng, liên kết kỳ nguồn và lưu gói, phí, chủ trạm tại thời điểm tạo.

Kỳ ACTIVE còn hiệu lực được gia hạn sớm thành kỳ PENDING bắt đầu khi kỳ hiện tại kết thúc. Với kỳ EXPIRED, kỳ mới bắt đầu tại thời điểm gia hạn hợp lệ. Không gia hạn từ PENDING, SUSPENDED hoặc CANCELLED để vượt điều kiện quản trị.

Kỳ nguồn phải là kỳ mới nhất và chưa có kỳ kế tiếp. Mỗi nguồn chỉ có một kỳ kế tiếp trực tiếp, kể cả kỳ kế tiếp đó đã bị hủy. Phạm vi hiện tại chỉ đặt trước một kỳ, không gia hạn tiếp từ PENDING.

## 4. Hủy một kỳ và kiểm soát xuyên kỳ
Hủy một kỳ chỉ chấm dứt kỳ được chọn, không tự hủy kỳ kế tiếp. Chấm dứt quan hệ cấp phép của trạm là quyết định rộng hơn, không được diễn đạt chung thành hủy license.

Vault mô tả COMPLIANCE_HOLD và REVOKED ở cấp trạm để chặn cấp mới, gia hạn hoặc kích hoạt xuyên kỳ. Đây là template cần triển khai đầy đủ; không suy ra trạng thái một kỳ tự thực hiện tất cả kiểm soát đó. Không dùng kỳ mới để vượt qua quyết định đình chỉ còn hiệu lực.

## 5. Giao dịch tài xế
License thay đổi không tự sửa trạng thái trạm, trụ hoặc cổng, không tự hủy booking đã xác nhận hay phiên đang chạy. Sự cố hoặc quyết định an toàn khiến trạm không phục vụ được có quy trình xử lý riêng.

## Căn cứ nghiệp vụ
- ChargeOps Vault — License/license-admin-backend-api-design: Glossary, mục 7.4–7.7, kỳ mới, lineage, kiểm soát cấp phép và recovery.
- License/license-station-eligibility-policy.vi: bảo toàn giao dịch đã được nhận.$document$,
'4.9.0','vi');

-- Source: docs/ChargeOps/Booking/README.md | Staff invariant
-- Source: docs/ChargeOps/Booking/01-backend-design.md | Section 7
INSERT INTO chargeops_legal_seed VALUES (
'station-staff-access-policy','OPERATIONAL_REGULATION','OWNER',
'Quy định phân công nhân viên và phạm vi truy cập','ChargeOps · Nhân viên trạm',
'Staff là assignment ACTIVE theo trạm; tách quyền vận hành khỏi tiền, doanh thu, ngân hàng và chuyển hoàn.',
$document$# Quy định phân công nhân viên và phạm vi truy cập

> Phạm vi: Quy tắc phân quyền theo Vault và thiết kế Booking v4.9. Nội dung không xác nhận mọi màn hình hoặc nghiệp vụ tài chính đã được triển khai.

## 1. Bản chất quyền nhân viên
Staff là tài khoản được chủ trạm phân công vào một trạm, không phải role tài khoản độc lập. Quyền chỉ có hiệu lực khi phân công ACTIVE. Có tài khoản hoặc từng được phân công không đồng nghĩa đang có quyền vận hành.

Một người chỉ thao tác trong phạm vi trạm được giao. Máy chủ kiểm tra quyền cho từng yêu cầu, không dựa riêng vào việc giao diện có nút thao tác.

## 2. Công việc vận hành
Nhân viên tiếp cận thông tin vận hành, booking và phiếu hỗ trợ cần thiết theo quyền tại trạm, hỗ trợ giám sát và xác minh sự cố. Ghi nhận lỗi phải gắn với trạm và booking liên quan, có căn cứ để xử lý tiếp.

Khi phân công không còn ACTIVE, quyền phát sinh từ phân công đó không còn áp dụng cho yêu cầu mới. Không dùng quyền tại một trạm để xem hoặc sửa dữ liệu trạm khác.

## 3. Giới hạn dữ liệu tài chính
Dữ liệu trả cho nhân viên vận hành không bao gồm số tiền, doanh thu, thông tin thanh toán và trường ngân hàng. Nhân viên không quản lý license, thực hiện hoàn/chi trả hoặc tự xác nhận đã chuyển tiền.

Nhân viên xác minh lỗi trạm không thay thế quyết định hay thao tác tài chính có thẩm quyền. Phiếu đã được nhân viên xử lý không đồng nghĩa tài xế đã nhận hoàn.

## 4. Phân biệt chủ thể
Tài xế chỉ xem và thao tác booking, phiếu hỗ trợ của mình. Chủ trạm quản lý trạm và tài chính của mình theo quyền, có thể báo không phục vụ được nhưng không tự ghi kết quả hoàn tiền. Quản trị viên xử lý đối soát, hoàn, chi trả và tranh chấp theo chức năng.

Khi phát hiện truy cập vượt phạm vi, cần ghi nhận để kiểm tra phân công và quyền; không chia sẻ tài khoản chủ trạm cho nhân viên để vượt giới hạn.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/README: Staff là assignment, không có role STAFF hoặc quyền chuyển tiền.
- Booking/01-backend-design: mục 7, quyền Driver/Owner/Staff/Admin và giới hạn trường tài chính.$document$,
'4.9.0','vi');
-- Source: docs/ChargeOps/Connector/chargepoint-connector-invariants-t17.md | Sections 1-3
-- Source: docs/ChargeOps/Charge Point/api_design_document_chargepoint_connector_admin_apis.md | Sections 1-2
-- Source: docs/ChargeOps/Booking/01-backend-design.md | Section 7
INSERT INTO chargeops_legal_seed VALUES (
'station-equipment-operation-policy','OPERATIONAL_REGULATION','OWNER',
'Quy định quản lý trụ sạc, cổng sạc và bảo trì','ChargeOps · Thiết bị',
'Phân cấp thiết bị, tương thích AC/DC, công suất từng cổng, trạng thái cấp thiết bị/vận hành và xử lý bảo trì.',
$document$# Quy định quản lý trụ sạc, cổng sạc và bảo trì

> Phạm vi: Ràng buộc thiết bị trong Vault và thiết kế xử lý booking khi bảo trì. Trạng thái mô phỏng không phải chứng nhận thiết bị hoặc bằng chứng đo điện thực tế.

## 1. Phân cấp và định danh
Trạm gồm các trụ sạc; mỗi trụ có cổng sạc trực thuộc. Mã trụ/cổng xác định thiết bị trong phạm vi quản lý. Chủ trạm duy trì thông tin và hướng dẫn QR đúng cổng, không hoán đổi mã khiến tài xế nhận nhầm vị trí.

Phê duyệt trạm, cấp license và kích hoạt thiết bị là các bước khác nhau. Có license không có nghĩa mọi trụ/cổng đã sẵn sàng nhận tài xế.

## 2. Cấu hình phần cứng
Theo T17, công suất một cổng trong khoảng 3–360 kW. Nếu khai báo công suất tối đa của trụ, giá trị trong khoảng 3–720 kW và không nhỏ hơn công suất từng cổng thuộc trụ. Quy tắc này so sánh từng cổng với trụ, không tự đổi thành tổng công suất mọi cổng khi mô hình chưa quy định.

TYPE2 tương ứng AC; CCS2, CHAdeMO và GB/T tương ứng DC trong mô hình dự án. Loại đầu, dòng điện và công suất phải nhất quán. Những trường phần cứng bị khóa theo vòng đời không được thay đổi như cập nhật tên hiển thị.

## 3. Phân biệt trạng thái
Trạng thái cấp/kích hoạt của trụ khác trạng thái vận hành. Trụ có trạng thái vận hành AVAILABLE, OFFLINE hoặc MAINTENANCE. Cổng có runtime AVAILABLE, IN_USE hoặc OFFLINE; không dùng FAULTED hoặc MAINTENANCE như một giá trị runtime của cổng khi enum không hỗ trợ.

License hết hạn không tự đặt cổng OFFLINE hoặc trụ MAINTENANCE. Cổng AVAILABLE cũng không thay thế điều kiện license và phê duyệt trạm.

## 4. Bảo trì và sự cố
Cập nhật lịch, giá hoặc bảo trì thông thường phải xét booking đã bán và bảo toàn giá đã chốt. Sự cố khẩn cấp xử lý riêng, có lý do, lịch sử và danh sách booking bị ảnh hưởng; không ép qua bảo trì thông thường đang bị chặn.

Trạm không phục vụ được phải phối hợp xác minh để xử lý booking, không mặc định tự đổi khách sang cổng khác. Lỗi trạm xác nhận trong MVP có thể hoàn đủ giá gói, kể cả giữa phiên.

## 5. Giới hạn dữ liệu
Công suất danh định, trạng thái logic và dữ liệu phiên mô phỏng không chứng minh lượng điện tài xế thực nhận. Không sử dụng các giá trị đó như chứng nhận an toàn hoặc cam kết hiệu suất thiết bị.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Connector/chargepoint-connector-invariants-t17: mục 1–3, giới hạn công suất từng cổng và tương thích đầu sạc.
- Charge Point/api_design_document_chargepoint_connector_admin_apis: mục 1–2, phân cấp, trạng thái, cấp thiết bị.
- Booking/01-backend-design: mục 7, bảo trì và bảo vệ booking đã bán.$document$,
'4.9.0','vi');

-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 2, 6, 7
-- Source: docs/ChargeOps/Booking/README.md | No commission; simulator; payout tasks
INSERT INTO chargeops_legal_seed VALUES (
'owner-payout-and-adjustment-policy','OPERATIONAL_REGULATION','OWNER',
'Quy định đối soát, chi trả và điều chỉnh cho chủ trạm','ChargeOps · Chi trả',
'Điều kiện chi tiền gói, giữ nghĩa vụ hoàn/tranh chấp, tránh chi trùng và xử lý sự cố sau khi đã chi.',
$document$# Quy định đối soát, chi trả và điều chỉnh cho chủ trạm

> Phạm vi: Thiết kế chi trả MVP v4.9 bằng mô phỏng có lịch sử đối soát; tài liệu không xác nhận chức năng đã triển khai hoặc đã chuyển tiền ngân hàng thật.

## 1. Khoản tiền làm căn cứ chi trả
Chi trả từ tiền gói đã áp dụng cho booking của chủ trạm, sau khi xét khoản hoàn, nghĩa vụ đang giữ và điều chỉnh. Không lấy toàn bộ tiền đã nhận, bao gồm tiền dư, làm doanh thu đủ điều kiện chi.

ChargeOps không thu hoa hồng booking. Phí license là khoản B2B riêng, không phải tiền gói hoặc bằng chứng một khoản đã thu qua ngân hàng.

## 2. Điều kiện lập chi trả
Booking phải kết thúc phù hợp, có tiền gói đã áp dụng và không bị giữ bởi tranh chấp sự cố chưa kết luận. Khoản đã phân bổ cho lần chi đang chờ, đang xử lý, đã thành công hoặc thất bại còn chờ xử lý không được gom lại để chi lần hai.

Booking CANCELLED hoặc no-show không hoàn có thể còn tiền thuộc chủ trạm. Booking có hoàn phải trừ cả khoản đã trả và nghĩa vụ đang xử lý, không chỉ trừ khoản hoàn thành công.

## 3. Xem trước, lập và thực hiện
Xem trước khoản chi chưa giữ tiền. Khi lập và bắt đầu thực hiện phải kiểm tra lại, tránh hai thao tác cùng gom một khoản. Chi trả thất bại không tự làm tiền trở lại đủ điều kiện lập lệnh khác khi nghĩa vụ cũ còn chờ thử lại.

Trong demo, chi trả và đối soát dùng mô phỏng có lịch sử. Trạng thái thành công của mô phỏng không chứng minh chủ trạm nhận tiền qua ngân hàng.

## 4. Sự cố phát sinh sau đó
Nếu phát sinh hoàn khi lô chi còn PENDING và chưa thực hiện, lô có thể bị hủy theo quy trình để tính lại. Khi đã PROCESSING hoặc chưa rõ kết quả, không coi lệnh bên ngoài đã hủy chỉ bằng sửa trạng thái nội bộ.

Nếu đã chi thành công rồi mới xác nhận lỗi trạm, vẫn ghi nghĩa vụ hoàn tài xế và điều chỉnh cần thu hồi từ chủ trạm. Giữ lịch sử chi thành công. Trường hợp chưa thực sự chi không được ghi như chủ trạm đã nhận tiền để đòi lại.

## 5. Phân quyền
Chủ trạm theo dõi tài chính của mình. Nhân viên vận hành không xem số tiền, doanh thu, ngân hàng hoặc thực hiện chi trả. Quản trị viên xử lý đối soát, hoàn và chi theo thẩm quyền. Ticket và lệnh chuyển tiền liên kết với nhau nhưng không đồng nhất.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/01-backend-design: mục 2, 6 và 7, mô phỏng, nghĩa vụ hoàn, phân bổ chi, điều chỉnh và quyền tài chính.
- Booking/README: không hoa hồng booking, phạm vi simulator và các task chi trả.$document$,
'4.9.0','vi');

WITH applied AS (
    INSERT INTO legal_documents AS current_doc
        (slug, doc_type, target_audience, title, eyebrow, summary, content, version, locale)
    SELECT slug, doc_type, target_audience, title, eyebrow, summary, content, version, locale
    FROM chargeops_legal_seed
    ON CONFLICT (slug) DO UPDATE SET
        doc_type = EXCLUDED.doc_type,
        target_audience = EXCLUDED.target_audience,
        title = EXCLUDED.title,
        eyebrow = EXCLUDED.eyebrow,
        summary = EXCLUDED.summary,
        content = EXCLUDED.content,
        version = EXCLUDED.version,
        locale = EXCLUDED.locale,
        updated_at = transaction_timestamp(),
        updated_by = NULL -- system script, not the previous human editor
    WHERE ROW(current_doc.doc_type, current_doc.target_audience, current_doc.title,
              current_doc.eyebrow, current_doc.summary, current_doc.content,
              current_doc.version, current_doc.locale)
          IS DISTINCT FROM
          ROW(EXCLUDED.doc_type, EXCLUDED.target_audience, EXCLUDED.title,
              EXCLUDED.eyebrow, EXCLUDED.summary, EXCLUDED.content,
              EXCLUDED.version, EXCLUDED.locale)
    RETURNING slug
)
SELECT (SELECT count(*) FROM chargeops_legal_seed) AS catalog_documents,
       count(*) AS inserted_or_updated
FROM applied;

-- Report publication state. Existing inactive/deleted rows are NOT resurrected.
SELECT d.slug, d.doc_type, d.target_audience, d.version, d.is_active,
       d.effective_from, d.deleted_at
FROM legal_documents d JOIN chargeops_legal_seed s USING (slug)
ORDER BY d.doc_type, d.slug;
COMMIT;
