-- ChargeOps: manual legal/business catalog, UTF-8; not a Flyway migration.
-- Apply V25 first. Execute the WHOLE file in a fresh session.
-- psql -X -v ON_ERROR_STOP=1 -d chargeops -f scripts/sql/seed-legal-documents.sql
-- Rerun deliberately overwrites editorial fields of the listed slugs.
-- Preserves IDs, creation audit, effective dates, inactive and soft-delete state.
-- Does not delete other documents, change system_configs or repair Flyway history.
-- New documents are active. Review the catalog before running on a shared DB.
-- Unchanged documents retain baseline 4.9.0. The bilingual pilot uses its editorial revision.
-- Canonical six-topic bilingual bodies: docs/ChargeOps/Knowledge/Published/.
-- Reviewed actor/Simulator drift against Booking #34/#37/#54/#55 on 2026-10-08.

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

> Phạm vi: Diễn giải nghiệp vụ theo ChargeOps Vault, đối chiếu 08/09/2026; cập nhật phạm vi tài chính theo #34/#37 ngày 08/10/2026. Các luồng Booking–Payment được mô tả theo thiết kế v4.9; tài liệu không xác nhận mọi chức năng đã triển khai. Quyền thao tác và tham số từng giao dịch căn cứ dữ liệu hệ thống trả về.


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
- ChargeOps Vault: Booking/06-bkg-002-system-config-and-booking-policy.$document$,
'bkg067-2026-10-08-r1',
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

> Phạm vi: Diễn giải nghiệp vụ theo ChargeOps Vault, đối chiếu 08/09/2026; cập nhật phạm vi tài chính theo #34/#37 ngày 08/10/2026. Các luồng Booking–Payment được mô tả theo thiết kế v4.9; tài liệu không xác nhận mọi chức năng đã triển khai. Quyền thao tác và tham số từng giao dịch căn cứ dữ liệu hệ thống trả về.


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
2. Sự cố trạm được xác nhận đủ điều kiện hoàn **100% giá gói** trong MVP, kể cả giữa phiên; không hoàn trùng phần đã hoàn. Việc lập hoặc đóng phiếu hỗ trợ không tự thực hiện chuyển tiền. Owner đúng trạm nhận trách nhiệm thường lệ; Admin chỉ quyết định tài chính trong vụ việc cụ thể đã chuyển lên. Staff chỉ ghi nhận bằng chứng kỹ thuật. Refund core thực hiện bằng Simulator, Owner được thử lại khi hệ thống cho phép, không tự ghi kết quả chuyển tiền.

## Căn cứ nghiệp vụ
- ChargeOps Vault: Booking/README.
- ChargeOps Vault: Booking/01-backend-design.
- ChargeOps Vault: Connector/chargepoint-connector-invariants-t17.$document$,
'bkg067-2026-10-08-r1',
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
'cancellation-and-refund-policy','OPERATIONAL_REGULATION','ALL','Quy định hủy và hoàn tiền','ChargeOps · Hủy và hoàn tiền','Ân hạn từ xác nhận thanh toán, đồng ý số hoàn hiện tại và hoàn Simulator.',
$document$# Hủy và hoàn tiền

Phiên bản nội dung: **bkg067-2026-10-08-r1**. Phạm vi: ChargeOps Booking v4.9, hoàn tiền bằng Simulator. Các ví dụ dùng cấu hình mặc định; hạn và số tiền của booking cụ thể do máy chủ trả về.

## CR-01 — Hủy khi chưa thanh toán

Booking PENDING còn hạn giữ chỗ có thể hủy, không phát sinh hoàn giá gói vì chưa có khoản thanh toán áp dụng. Booking hết hạn giữ chỗ không được hủy bằng thao tác này; cần đọc trạng thái mới. Khoản tiền đến muộn hoặc chưa được áp dụng được đối soát riêng.

## CR-02 — Hủy do đổi ý sau thanh toán

Booking CONFIRMED, chưa check-in được hoàn **100% giá gói đã thanh toán** nếu máy chủ xử lý hủy **trước** hạn hủy hoàn tiền. Hạn này là mốc sớm hơn giữa lần đầu xác nhận thanh toán cộng số phút ân hạn và giờ bắt đầu đã đặt. Mặc định ân hạn là 10 phút.

Đúng hạn hoặc sau hạn, hủy do đổi ý hoàn **0%**. Khi còn CONFIRMED và cửa sổ hủy vẫn mở, tài xế có thể hủy với mức hoàn 0%; tới hạn đóng check-in, hệ thống từ chối thao tác hủy và áp dụng xử lý no-show phù hợp. Booking đã check-in không dùng hủy do đổi ý. No-show thông thường hoàn 0%; kết thúc phiên sớm không tự giảm giá gói. Không có mức hoàn 50% hoặc hoàn theo số phút chưa sử dụng.

Ví dụ xác nhận lúc 08:58, bắt đầu 11:00, ân hạn 10 phút: hạn là 09:08. Xử lý hủy lúc 09:07:59 hoàn đủ giá gói; đúng 09:08 hoàn 0% do đổi ý. Dùng hạn đã lưu của booking cũ; thay đổi cấu hình sau đó không kéo dài hoặc rút ngắn hạn này.

## CR-03 — Đồng ý với số tiền hoàn hiện tại

Trước khi gửi hủy, ứng dụng hiển thị khoản hoàn để tài xế đồng ý. Máy chủ kiểm tra lại trạng thái, phiên bản booking, số tiền hoàn và phiên bản chính sách.

Nếu các điều kiện đã đổi, lỗi **BKG_CANCELLATION_CHANGED (409)** nghĩa là **chưa hủy**. Ứng dụng đọc booking hiện tại, hiển thị lại khoản hoàn và yêu cầu tài xế xác nhận lại. Không tự gửi hủy 0% khi người dùng vừa đồng ý 100%.

## CR-04 — Lỗi trạm là quy trình khác

Khi trạm không phục vụ được, tài xế báo sự cố thay vì dùng hủy do đổi ý. Owner đúng trạm nhận trách nhiệm theo quyền; Admin chỉ quyết định tài chính trong vụ việc cụ thể đã được chuyển lên. Staff ghi nhận bằng chứng kỹ thuật, không quyết định trách nhiệm hoặc hoàn tiền.

Trách nhiệm lỗi trạm được xác lập hợp lệ cùng khoản thanh toán/receipt hợp lệ tạo nghĩa vụ hoàn **100% giá gói**, kể cả giữa phiên hoặc sau no-show. Phiên đang chạy cần được xử lý an toàn qua quy trình sự cố trước quyết định tài chính. Ticket, finding kỹ thuật hoặc Incident riêng lẻ chưa đủ cấp quyền hoàn; không hoàn trùng giá gói đã hoàn. Không suy ra quyền hoàn từ việc ticket đã đóng.

## CR-05 — Nghĩa vụ hoàn và kết quả thực hiện

Hủy thành công và hoàn thành công là hai kết quả khác nhau. Nghĩa vụ hoàn có trạng thái PENDING hoặc SUCCEEDED; mỗi lần thực hiện có trạng thái STARTED, SUCCEEDED hoặc FAILED. Lần thực hiện thất bại vẫn để nghĩa vụ ở PENDING.

Refund core có thể phát lần thực hiện đầu tiên sau commit theo chính sách đã duyệt. Khi lần trước đã thất bại và quyền/thông tin hiện tại cho phép, Owner có thể thử lại theo thao tác của hệ thống. Không tự phát lần mới khi lần trước STARTED hoặc chưa rõ kết quả; không tự ghi nhận đã chuyển tiền. Không cam kết thử lại tự động. Khoản dư chưa áp dụng cho gói không thuộc nghĩa vụ hoàn giá gói này.

Hiện tại kết quả hoàn do Simulator cung cấp, không chứng minh có chuyển tiền ngân hàng thật. Sổ đối soát Owner không phải số dư ví có thể rút; không có chi trả Owner trong phạm vi hiện tại.

## Câu hỏi thường gặp

**Tôi bấm hủy trước hạn nhưng máy chủ nhận sau hạn?** Máy chủ tính theo thời điểm xử lý, nên số hoàn có thể đổi. Cần xác nhận lại nếu nhận lỗi ở CR-03.

**Ticket đã đóng, tôi đã được hoàn chưa?** Chưa thể kết luận; cần xem nghĩa vụ và kết quả thực hiện hoàn (CR-04, CR-05).

**Chatbot có quyết định số tiền của booking tôi không?** Không suy đoán từ tài liệu. Xem số tiền/hạn/trạng thái hiện tại trong ứng dụng hoặc API có quyền truy cập.

## Căn cứ và điều hướng

- 00 - ChargeOps Knowledge Hub · Bộ nguồn BKG-067.
- Hợp đồng API, xác nhận hủy và lỗi 409.
- Cấu hình và snapshot thời gian.
- Trách nhiệm lỗi trạm.
- Phạm vi hoàn Simulator.
- Bản tương đương tiếng Anh: cancellation-and-refunds-en-version.$document$,
'bkg067-2026-10-08-r1','vi');
-- Source: docs/ChargeOps/Booking/README.md | Check-in invariant
-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 4, 5, 7
-- Source: docs/ChargeOps/Booking/02-api-contract.md | QR flow; capabilities and errors
-- Source: docs/ChargeOps/Knowledge/Published/check-in-and-no-show.vi.md
INSERT INTO chargeops_legal_seed VALUES (
'qr-check-in-and-no-show-policy',
'OPERATIONAL_REGULATION',
'ALL',
'Check-in, vắng mặt và kết thúc phiên',
'ChargeOps Booking v4.9',
'Quy tắc Booking v4.9; Simulator, snapshot và quyền thao tác theo API.',
$document$# Check-in, vắng mặt và kết thúc phiên

Phiên bản nội dung: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9; phiên sạc và thanh toán trong demo là mô phỏng. Ví dụ dùng cấu hình mặc định, giờ Việt Nam. Deadline thực tế của booking lấy từ máy chủ và snapshot, không tính lại bằng cấu hình mới.

## CI-01 — Cửa sổ check-in

Booking đã được xác nhận thanh toán hợp lệ mới có thể check-in. Cửa sổ mở từ `startAt` (bao gồm) đến **trước** `checkInDeadline` (loại trừ), mặc định `endAt - 15 phút`. Không dùng `startAt + 15 phút`. Phiên 14:00–15:00 cho phép check-in từ 14:00 đến trước 14:45; đúng 14:45 đã đóng. Đồng hồ máy chủ quyết định, không phải thời điểm bấm hoặc đồng hồ điện thoại.

## CI-02 — Quét QR và xác nhận

Quét QR hợp lệ của đúng cổng sạc đã đặt để xem trước, rồi xác nhận check-in theo ứng dụng. Xem trước hoặc quét mã chưa chứng minh check-in thành công. Máy chủ kiểm tra quyền, booking, cổng, cửa sổ và mã xác nhận; mã hết hạn/đã dùng cần làm theo hướng dẫn quét lại. Không chia sẻ mã hoặc dùng QR của cổng khác để vượt kiểm tra.

## CI-03 — No-show và đến muộn

Tại hoặc sau cutoff, booking CONFIRMED chưa check-in được ghi nhận CANCELLED với lý do NO_SHOW. No-show thông thường hoàn **0%**. Đến muộn không lùi `endAt`, không kéo dài thời lượng đã mua và không tự cấp khoản hoàn cho thời gian chưa dùng.

No-show không xóa quyền yêu cầu xác minh lỗi trạm. Nếu Owner nhận trách nhiệm hoặc Admin grant trong đúng case escalated, đồng thời đủ điều kiện receipt/payment của refund core, nghĩa vụ hoàn đủ giá gói có thể được xác lập; không hoàn trùng. Ticket hoặc Staff finding riêng lẻ chưa cấp khoản hoàn.

## CI-04 — Kết thúc phiên và sự cố

Booking CHECKED_IN/CHARGING không còn thuộc luồng hủy tự nguyện. Kết thúc sớm không tự phát sinh hoàn theo phút chưa dùng. Nhả lịch không cam kết bán lại 15 phút cuối; đặt mới vẫn chịu điều kiện lịch, lead time và bước thời gian hiện hành.

Khi có sự cố trong phiên, báo hỗ trợ và xử lý an toàn qua luồng Incident/resolve-session trước quyết định tài chính cho booking terminal. Đừng hiểu thao tác dừng phiên, Incident hoặc đóng ticket là quyết định hoàn. Lỗi trạm đủ căn cứ áp dụng hoàn **100% giá gói**, kể cả giữa phiên, theo trách nhiệm và điều kiện thu tiền hợp lệ; kết quả thực thi theo dõi riêng.

## CI-05 — Cấu hình hiện hành và booking cũ

Thông tin trước đặt chỗ dùng policy hiện hành. Hạn check-in, giá và deadline của đơn đã tạo dùng dữ liệu đã chốt của chính booking. Thay cấu hình không gia hạn đơn cũ. Khi chưa tải được dữ liệu, ứng dụng/chatbot phải nêu chưa đủ thông tin, không dựng deadline hoặc quyền hoàn.

## Căn cứ và điều hướng

- glossary-and-business-rules-ssot: BR-BOK-04/05/06/07, BR-PAY-07/08.
- 06-bkg-002-system-config-and-booking-policy: cấu hình và snapshot.
- 37-bkg-056-station-failure-cancellation-and-refund-alignment.vi: lỗi trạm.
- check-in-and-no-show-en-version: bản Anh cùng revision.$document$,
'bkg067-2026-10-09-r1','vi');

-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 2, 4, 6
-- Source: docs/ChargeOps/Booking/02-api-contract.md | Payment flow and scenarios
INSERT INTO chargeops_legal_seed VALUES (
'payment-reconciliation-policy','OPERATIONAL_REGULATION','ALL','Quy định đặt chỗ và thanh toán','ChargeOps · Thanh toán','Giá gói, hạn giữ chỗ, xác nhận thanh toán và đối soát Simulator.',
$document$# Đặt chỗ và thanh toán

Phiên bản nội dung: **bkg067-2026-10-08-r1**. Phạm vi: ChargeOps Booking v4.9, thanh toán bằng Simulator. Múi giờ hiển thị: Việt Nam (Asia/Ho_Chi_Minh). Các ví dụ dùng cấu hình mặc định; hạn cụ thể của từng booking do máy chủ trả về.

## BP-01 — Giá gói và xác nhận đặt chỗ

Tài xế thanh toán toàn bộ giá gói thời gian, không đặt cọc, không nạp ví. Không thu hoa hồng hoặc phí booking riêng; phí license của chủ trạm là khoản khác. Giá gói không phải hóa đơn theo điện năng thực đo.

Xem giá dự tính chưa giữ chỗ. Khi tài xế đồng ý và hệ thống tạo booking thành công, booking ở trạng thái PENDING, có giá đã chốt và hạn giữ chỗ. Nếu giá hoặc chính sách thay đổi trước khi tạo, hệ thống yêu cầu tài xế xem và đồng ý lại.

## BP-02 — Hạn giữ chỗ thanh toán

Mặc định giữ chỗ **10 phút tính từ lúc tạo booking**. Tạo dữ liệu thanh toán hoặc thử lại không kéo dài hạn này. Thanh toán chỉ được áp dụng khi máy chủ xử lý trước hạn của booking và trước hạn thanh toán riêng nếu có. Tại đúng hạn, cửa sổ đã đóng.

Ví dụ tạo booking lúc 08:50, hạn giữ chỗ 09:00: xác nhận lúc 08:59 còn trong hạn; lúc 09:00 đã hết hạn. Đồng hồ trên điện thoại và thời gian trong thông báo thanh toán không khôi phục chỗ hết hạn.

## BP-03 — Khi nào booking được xác nhận?

Booking chuyển từ PENDING sang CONFIRMED khi hệ thống chấp nhận khoản thanh toán hợp lệ, đúng số tiền, loại tiền và tham chiếu trong cửa sổ còn hiệu lực. Trang báo thanh toán thành công hoặc việc quay lại ứng dụng chưa chứng minh booking đã CONFIRMED; cần đọc trạng thái từ ChargeOps.

Lần xác nhận hợp lệ đầu tiên ghi thời điểm xác nhận thanh toán và hạn hủy hoàn tiền. Thông báo lặp không đặt lại các mốc này. Một thông báo thất bại đến sau không được hạ cấp khoản thanh toán đã thành công.

## BP-04 — Tiền thiếu, thừa, không khớp hoặc đến muộn

Nhiều khoản thiếu không tự cộng dồn để xác nhận gói. Khoản thừa không tự được cắt một phần để xác nhận. Khoản không khớp hoặc đến muộn được lưu để đối soát riêng; không tự hồi sinh booking EXPIRED hoặc CANCELLED.

Một thông báo lặp về cùng giao dịch không tạo lần thu thứ hai. Hai khoản tiền vào có tham chiếu khác nhau là hai receipt cần theo dõi. Nếu gói 120.000 đồng đã được thanh toán đúng rồi có thêm khoản 120.000 đồng, khoản thứ hai chưa được áp dụng cho gói. Không cam kết tự động hoàn khoản dư; xử lý khoản dư và hủy gói là hai việc riêng.

## BP-05 — Hai đồng hồ khác nhau

Hạn giữ chỗ bắt đầu từ lúc **tạo booking**. Ân hạn hủy mặc định 10 phút bắt đầu từ lần đầu **xác nhận thanh toán**, bị giới hạn bởi giờ bắt đầu đã đặt. Không cộng hai khoảng này thành một hạn chung.

Ví dụ tạo lúc 08:50, xác nhận lúc 08:58, phiên bắt đầu 11:00: hạn giữ chỗ là 09:00, hạn hủy hoàn đủ giá gói là 09:08. Đúng 09:08 không còn hoàn do đổi ý. Cấu hình mới không tính lại hạn đã lưu của booking cũ.

## BP-06 — Ý nghĩa thanh toán trong demo

Hiện tại dùng Simulator, chưa chuyển tiền qua ngân hàng thật. Kết quả thu/hoàn thành công của Simulator là kết quả mô phỏng, không chứng minh người dùng đã nhận tiền thật. Không có chức năng rút ví hoặc chi trả cho chủ trạm trong phạm vi hiện tại.

## Câu hỏi thường gặp

**Tôi thử thanh toán lại thì có thêm 10 phút không?** Không, vẫn dùng hạn giữ chỗ ban đầu (BP-02).

**Chatbot có thể xác nhận tôi đã trả tiền chưa?** Tài liệu này chỉ giải thích quy tắc. Trạng thái, giá và hạn của booking cụ thể phải đọc từ ứng dụng/API có quyền truy cập; chatbot không được đoán từ ví dụ (BP-03).

**Tôi cần hủy?** Đọc Hủy và hoàn tiền, đặc biệt CR-02 và CR-03.

## Căn cứ và điều hướng

- 00 - ChargeOps Knowledge Hub · Bộ nguồn BKG-067.
- Hợp đồng API, luồng thanh toán và xác nhận hủy.
- Cấu hình thời gian.
- Phạm vi Simulator và tài chính Owner.
- Bản tương đương tiếng Anh: booking-and-payment-en-version.$document$,
'bkg067-2026-10-08-r1','vi');

-- Source: docs/ChargeOps/Booking/01-backend-design.md | Sections 2, 6, 7
-- Source: docs/ChargeOps/Booking/README.md | Station failure invariant; MVP scope
-- Source: docs/ChargeOps/Knowledge/Published/station-failure-and-responsibility.vi.md
INSERT INTO chargeops_legal_seed VALUES (
'station-incident-and-support-policy',
'OPERATIONAL_REGULATION',
'ALL',
'Lỗi trạm và trách nhiệm',
'ChargeOps Booking v4.9',
'Quy tắc Booking v4.9; Simulator, snapshot và quyền thao tác theo API.',
$document$# Lỗi trạm và trách nhiệm Owner, Staff, Admin

Phiên bản nội dung: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9, tài chính Simulator. Đây là hướng dẫn quy tắc chung; quyền và trạng thái của vụ việc cụ thể do API có quyền trả về.

## SF-01 — Chứng cứ và an toàn vận hành

Ticket, Incident và finding ghi lại thông tin/kết luận kỹ thuật để xử lý sự cố. Chúng không tự xác lập nghĩa vụ hoàn, không chứng minh tiền đã trả. Với phiên đang hoạt động, xử lý an toàn qua Incident/resolve-session trước quyết định tài chính cho booking terminal. Luồng booking chưa check-in dùng command hủy phù hợp, không tự đổi trạng thái bằng nội dung chatbot.

## SF-02 — Quyền Staff

Staff được phân công ACTIVE đúng trạm có thể xử lý vận hành và ghi finding trong phạm vi/vòng ticket cho phép. Staff không quyết định trách nhiệm tài chính, không grant khoản hoàn và không thử lại Simulator refund. Finding STATION_FAILURE là chứng cứ, không phải quyết định hoàn tiền.

## SF-03 — Quyền Owner và escalation

Owner đúng scope trạm/snapshot booking có thể nhận trách nhiệm lỗi trạm theo điều kiện API. Owner vẫn có thể chủ động nhận trách nhiệm khi case đã escalated, không phải chờ Admin phân xử. Quyền ghi finding kỹ thuật tại trạm có thể bị khóa khi escalation; việc này không đồng nghĩa mất command nhận trách nhiệm độc lập.

Không suy quyền tài chính từ việc đang sở hữu trạm hoặc đóng vai handler: backend kiểm tra scope, snapshot và trạng thái. Owner chỉ retry khoản hoàn khi hệ thống cho phép; quyết định trách nhiệm không đảm bảo mọi attempt thành công.

## SF-04 — Quyền Admin

Admin rà chính sách tài chính trong **đúng case escalated đang có hiệu lực**. Finding bổ sung của Admin vẫn là chứng cứ; financial grant là command riêng có kiểm tra quyền và version. Grant đủ căn cứ xác lập nghĩa vụ hoàn đủ giá gói, kể cả Owner không đồng ý; insufficient evidence không tạo khoản hoàn mới hoặc hủy nghĩa vụ đã có.

Không có Admin queue refund toàn nền tảng, Manual Record chuyển tiền hoặc payout Owner trong demo. Điều phối handler, RETURN_TO_STATION và CLOSE_SUPPORT_CASE là thao tác hỗ trợ riêng; chúng không thay financial decision hoặc kết quả refund.

## SF-05 — Phạm vi hoàn và trạng thái thực thi

Lỗi trạm được xác lập trách nhiệm hợp lệ và đủ điều kiện payment/receipt của refund core áp dụng **100% giá gói**, kể cả giữa phiên hoặc sau no-show. Không hoàn theo phần giờ còn lại và không hoàn trùng số đã hoàn. Khoản dư/UNAPPLIED đối soát riêng, không tự thuộc nghĩa vụ hoàn gói.

Nghĩa vụ PENDING chưa chứng minh hoàn thành công; attempt FAILED vẫn để nghĩa vụ PENDING. Theo dõi kết quả Simulator và chỉ retry khi đủ guard. Đóng ticket không là SUCCEEDED; Simulator không chứng minh giao dịch ngân hàng thật.

## Căn cứ và điều hướng

- 34-owner-simulator-finance-policy-and-root-refactor.vi: phạm vi actor/tài chính.
- 37-bkg-056-station-failure-cancellation-and-refund-alignment.vi: trách nhiệm lỗi trạm.
- 41-bkg-057-admin-refund-policy-backend-checkpoint.vi: rà chính sách đúng case.
- 53-bkg-075-dispatch-and-owner-retry-verification.vi: retry và attempt chưa rõ kết quả.
- station-failure-and-responsibility-en-version: bản Anh cùng revision.$document$,
'bkg067-2026-10-09-r1','vi');
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
Tài xế chỉ xem và thao tác booking, phiếu hỗ trợ của mình. Owner quản lý tài chính đúng trạm, nhận trách nhiệm thường lệ và thử lại hoàn Simulator khi hệ thống cho phép; không tự ghi kết quả chuyển tiền. Admin chỉ rà chính sách tài chính trong vụ việc cụ thể đã chuyển lên. Staff chỉ có quyền vận hành/bằng chứng, không quyết định trách nhiệm hoặc hoàn tiền. Phạm vi hiện tại không có chi trả Owner.

Khi phát hiện truy cập vượt phạm vi, cần ghi nhận để kiểm tra phân công và quyền; không chia sẻ tài khoản chủ trạm cho nhân viên để vượt giới hạn.

## Căn cứ nghiệp vụ
- ChargeOps Vault — Booking/README: Staff là assignment, không có role STAFF hoặc quyền chuyển tiền.
- Booking/01-backend-design: mục 7, quyền Driver/Owner/Staff/Admin và giới hạn trường tài chính.$document$,
'bkg067-2026-10-08-r1','vi');
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
-- Source: docs/ChargeOps/Booking/34-owner-simulator-finance-policy-and-root-refactor.vi.md
-- Source: docs/ChargeOps/Booking/54-bkg-064-owner-finance-ledger-and-simulator-summary.vi.md
-- Source: docs/ChargeOps/Knowledge/Published/refund-attempts-and-owner-ledger.vi.md
INSERT INTO chargeops_legal_seed VALUES (
'owner-payout-and-adjustment-policy',
'OPERATIONAL_REGULATION',
'OWNER',
'Nghĩa vụ hoàn, thử lại và sổ Owner',
'ChargeOps Booking v4.9',
'Quy tắc Booking v4.9; Simulator, snapshot và quyền thao tác theo API.',
$document$# Nghĩa vụ hoàn, thử lại và sổ đối soát Owner

Phiên bản nội dung: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9. Thu tiền và hoàn trong demo dùng Simulator; không có payout, ví rút tiền hoặc xác nhận ngân hàng thật.

## RL-01 — Nghĩa vụ khác lần thực thi

Refund là nghĩa vụ hoàn đủ giá gói theo policy/decision hợp lệ, có trạng thái PENDING hoặc SUCCEEDED. RefundAttempt là một lần thực thi với trạng thái STARTED, SUCCEEDED hoặc FAILED, giữ audit và idempotency. FAILED không xóa nghĩa vụ PENDING. Hủy booking, nhận trách nhiệm, grant hoặc đóng ticket thành công chưa chứng minh hoàn SUCCEEDED.

## RL-02 — Lần đầu và thử lại

Refund core có thể phát attempt đầu sau commit theo policy đã duyệt. Khi lần trước FAILED đã xác định kết quả, Owner đúng scope có thể retry nếu guard hiện hành cho phép. Không hứa retry tự động. Staff không retry; Admin không dùng queue hoàn toàn nền tảng hoặc Manual Record để ghi tiền đã chuyển.

Nếu attempt trước còn STARTED hoặc chưa rõ kết quả, không phát attempt mới để tránh hoàn trùng. Đọc trạng thái/khả năng thao tác từ API có quyền, theo hướng dẫn vận hành để đối soát kết quả cũ. Không đổi trạng thái bằng tay hoặc dùng idempotency key mới để vượt guard.

## RL-03 — Điều kiện receipt và chống hoàn trùng

Nghĩa vụ hoàn gói dựa trên payment/receipt hợp lệ đã áp dụng cho booking; lệnh có thể trả NOT_READY khi căn cứ chưa sẵn sàng. Khoản thiếu/thừa/đến muộn/không khớp ở UNAPPLIED không tự tạo khoản hoàn gói và không tự khôi phục booking. Khoản dư cần đối soát riêng; không cam kết tự trả ngân hàng.

Lỗi trạm hợp lệ hoàn 100% giá gói kể cả giữa phiên hoặc sau no-show, không hoàn lại phần đã SUCCEEDED. Đồng thời hoặc replay không được tạo khoản hoàn trùng; tuân thủ version/idempotency và điều kiện máy chủ trả về.

## RL-04 — Sổ Owner và cách đọc số liệu

Sổ đối soát Owner ghi thu tiền gói hợp lệ và hoàn Simulator theo phạm vi tài chính được cấp. Gross là tổng receipt APPLIED khớp giá gói, không gồm khoản UNAPPLIED. Refund đã SUCCEEDED mới giảm net; nghĩa vụ PENDING hiển thị riêng, không coi đã chuyển tiền. Net = gross - refunds SUCCEEDED trong cùng phạm vi/bộ lọc báo cáo.

Sổ này không là số dư ví, tiền có thể rút hoặc payout. Không cộng lịch sử attempt làm nhiều lần hoàn, không coi FAILED là đã trả. Booking dùng snapshot tài chính/Owner đã chốt; đổi chủ trạm không tự chuyển quyền và số liệu lịch sử sang Owner mới.

## RL-05 — Giới hạn tư vấn

FAQ giải thích trạng thái và quy tắc, không xác định số tiền hay quyền retry của đơn cụ thể khi thiếu API có quyền. Luôn kiểm tra obligation, attempt, căn cứ receipt và trạng thái mới nhất trước thao tác. Kết quả Simulator SUCCEEDED chỉ chứng minh hoàn mô phỏng; không báo ngân hàng đã nhận.

## Căn cứ và điều hướng

- 34-owner-simulator-finance-policy-and-root-refactor.vi: ranh giới Simulator.
- 53-bkg-075-dispatch-and-owner-retry-verification.vi: retry/STARTED.
- 55-bkg-065-owner-ledger-and-refund-full-flow-verification.vi: gross/refund/net.
- glossary-and-business-rules-ssot: BR-PAY-07/08/09.
- refund-attempts-and-owner-ledger-en-version: bản Anh cùng revision.$document$,
'bkg067-2026-10-09-r1','vi');

-- Published source: docs/ChargeOps/Knowledge/Published/booking-and-payment-en-version.md
INSERT INTO chargeops_legal_seed VALUES (
'payment-reconciliation-policy-en','OPERATIONAL_REGULATION','ALL','Booking and payment policy','ChargeOps · Payment','Package price, payment hold, confirmation and Simulator reconciliation.',
$document$# Booking and payment

Content revision: **bkg067-2026-10-08-r1**. Scope: ChargeOps Booking v4.9 with Simulator payments. Display time zone: Vietnam (Asia/Ho_Chi_Minh). Examples use default settings; the server supplies each booking's actual deadlines.

## BP-01 — Package price and booking acceptance

The driver prepays the full time-package price, without a deposit or wallet top-up. There is no separate booking commission or booking fee; the owner's license fee is separate. The package price is not an invoice based on measured energy consumption.

A price preview does not reserve a slot. After the driver agrees and the system successfully creates the booking, it is PENDING with a stored price and hold deadline. If the price or policy changes before creation, the system asks the driver to review and agree again.

## BP-02 — Payment hold deadline

The default hold is **10 minutes from booking creation**. Creating checkout data or retrying does not extend it. A payment can only be applied when the server processes it before the booking deadline and before any separate payment expiry. The window is closed at the exact deadline.

Example: a booking created at 08:50 expires at 09:00. Confirmation at 08:59 is within the hold; at 09:00 it is too late. The phone clock or the timestamp in a payment notification cannot restore an expired slot.

## BP-03 — When is a booking confirmed?

A booking changes from PENDING to CONFIRMED when the system accepts a valid payment with the correct amount, currency and reference within the active window. A success page or returning to the app does not prove that the booking is CONFIRMED; read its status from ChargeOps.

The first valid confirmation records the payment confirmation time and free-cancellation deadline. Repeated notifications do not reset them. A later failure notification must not downgrade an already successful payment.

## BP-04 — Underpayment, overpayment, mismatches and late payments

Multiple underpayments are not automatically combined to confirm a package. An overpayment is not automatically split to confirm it. Mismatched or late receipts are stored for separate reconciliation; they do not revive an EXPIRED or CANCELLED booking.

A repeated notification for the same transaction does not create a second receipt. Two incoming payments with different references are two receipts to track. If a VND 120,000 package has been correctly paid and another VND 120,000 arrives, the second receipt is not applied to the package. Automatic return of excess funds is not promised; excess-payment handling and package cancellation are separate matters.

## BP-05 — Two different clocks

The payment hold starts at **booking creation**. The default 10-minute cancellation grace starts at the first **payment confirmation** and is capped by the booked start time. Do not combine the two periods into one deadline.

Example: creation at 08:50, confirmation at 08:58 and a session starting at 11:00 mean a hold deadline of 09:00 and a full-refund cancellation deadline of 09:08. At exactly 09:08 there is no change-of-mind refund. New settings do not recalculate deadlines stored on existing bookings.

## BP-06 — What payment means in the demo

The current system uses Simulator payments, without real bank transfers. A successful Simulator collection or refund is a simulated outcome, not proof that real money reached a user. Wallet withdrawals and owner payouts are outside the current scope.

## Frequently asked questions

**Does retrying payment give me another 10 minutes?** No. The original hold deadline still applies (BP-02).

**Can the chatbot confirm that I have paid?** This document explains rules only. A specific booking's status, price and deadlines must come from an authorized app/API lookup; the chatbot must not infer them from examples (BP-03).

**How do I cancel?** Read Cancellation and refunds, especially CR-02 and CR-03.

## Sources and navigation

- 00 - ChargeOps Knowledge Hub · BKG-067 knowledge sources.
- API contract, payment and cancellation consent flows.
- Time configuration.
- Simulator and owner finance scope.
- Vietnamese equivalent: booking-and-payment.vi.$document$,
'bkg067-2026-10-08-r1','en');

-- Published source: docs/ChargeOps/Knowledge/Published/cancellation-and-refunds-en-version.md
INSERT INTO chargeops_legal_seed VALUES (
'cancellation-and-refund-policy-en','OPERATIONAL_REGULATION','ALL','Cancellation and refund policy','ChargeOps · Cancellation and refunds','Grace from payment confirmation, current refund consent and Simulator refunds.',
$document$# Cancellation and refunds

Content revision: **bkg067-2026-10-08-r1**. Scope: ChargeOps Booking v4.9 with Simulator refunds. Examples use default settings; the server supplies a specific booking's deadlines and amounts.

## CR-01 — Cancellation before payment

A PENDING booking with an active hold can be cancelled without a package refund because no payment has been applied. Once the hold expires, this cancellation command is unavailable; read the latest status. Late or unapplied receipts require separate reconciliation.

## CR-02 — Change-of-mind cancellation after payment

A CONFIRMED booking that has not been checked in receives **100% of the paid package price** if the server processes cancellation **before** the free-cancellation deadline. This deadline is the earlier of the first payment confirmation plus the grace period and the booked start time. The default grace period is 10 minutes.

At or after the deadline, a change-of-mind cancellation receives **0%**. While the booking remains CONFIRMED and the cancellation window is open, the driver may cancel for a zero refund; at the check-in cutoff the system rejects cancellation and applies the appropriate no-show handling. A checked-in booking cannot use change-of-mind cancellation. An ordinary no-show receives 0%; ending a session early does not reduce the package price. There is no 50% tier or refund calculated from unused minutes.

Example: payment confirmed at 08:58, booked start at 11:00 and a 10-minute grace period give a deadline of 09:08. Cancellation processed at 09:07:59 refunds the full package; at exactly 09:08 the change-of-mind refund is zero. Use the deadline stored on an existing booking; later configuration changes do not extend or shorten it.

## CR-03 — Consent to the current refund amount

Before submitting cancellation, the app shows the refund amount for the driver to accept. The server rechecks the state, booking version, refund amount and policy version.

If those conditions changed, **BKG_CANCELLATION_CHANGED (409)** means **the booking was not cancelled**. The app reads the current booking, shows the updated amount and asks the driver to confirm again. It must not silently submit a zero-refund cancellation after the user accepted a full refund.

## CR-04 — Station failure follows a separate process

If the station cannot provide service, the driver reports an incident instead of using change-of-mind cancellation. The station's Owner accepts responsibility within their authority; Admin makes financial decisions only within a specific escalated case. Staff record technical evidence and do not decide responsibility or refunds.

Valid station-failure responsibility together with a valid payment/receipt establishes an obligation to refund **100% of the package price**, including mid-session or after a no-show. An active session must be safely resolved through the incident process before a financial decision. A ticket, technical finding or Incident alone does not grant a refund; do not refund the same package amount twice. A closed ticket does not prove refund entitlement.

## CR-05 — Refund obligation and execution outcome

Successful cancellation and successful refund are separate outcomes. A refund obligation is PENDING or SUCCEEDED; each execution attempt is STARTED, SUCCEEDED or FAILED. A failed attempt leaves the obligation PENDING.

The refund core may dispatch the first attempt after commit under an approved policy. After a confirmed failure, the Owner may retry through the system when current permissions and conditions allow it. Do not start another attempt while the previous one is STARTED or its outcome is unknown; do not manually claim that money was transferred. Automatic retries are not promised. Excess receipts that were not applied to the package are outside this package-refund obligation.

Current refund outcomes come from the Simulator and do not prove real bank transfers. The Owner reconciliation ledger is not a withdrawable wallet balance; owner payouts are outside the current scope.

## Frequently asked questions

**What if I press Cancel before the deadline but the server processes it afterwards?** The server uses processing time, so the amount may change. Confirm again if you receive the error in CR-03.

**The ticket is closed. Have I received a refund?** That cannot be concluded from the ticket; check the refund obligation and execution outcome (CR-04, CR-05).

**Can the chatbot decide my booking's refund amount?** It must not infer it from this document. Read the current amount, deadline and state from the app or an authorized API.

## Sources and navigation

- 00 - ChargeOps Knowledge Hub · BKG-067 knowledge sources.
- API contract, cancellation consent and 409 responses.
- Time configuration and snapshots.
- Station-failure responsibility.
- Simulator refund scope.
- Vietnamese equivalent: cancellation-and-refunds.vi.$document$,
'bkg067-2026-10-08-r1','en');

-- Source: docs/ChargeOps/Knowledge/Published/check-in-and-no-show-en-version.md
INSERT INTO chargeops_legal_seed VALUES (
'qr-check-in-and-no-show-policy-en',
'OPERATIONAL_REGULATION',
'ALL',
'Check-in, no-show and session ending',
'ChargeOps Booking v4.9',
'Booking v4.9 rules; Simulator, snapshots and API-authorized actions.',
$document$# Check-in, no-show and session ending

Content revision: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9; charging sessions and payments in the demo are simulated. Examples use default configuration and Vietnam time. A booking's actual deadlines come from the server and its snapshot, without recalculation using new configuration.

## CI-01 — Check-in window

A booking must have valid confirmed payment before check-in. The window opens at `startAt` (inclusive) and closes **before** `checkInDeadline` (exclusive), by default `endAt - 15 minutes`. Do not use `startAt + 15 minutes`. For a 14:00–15:00 session, check-in is allowed from 14:00 until before 14:45; exactly 14:45 is closed. Server time governs, rather than button-press time or the phone clock.

## CI-02 — QR scanning and confirmation

Scan a valid QR code for the booked connector to preview, then confirm check-in in the app. Previewing or scanning does not prove successful check-in. The server checks authorization, booking, connector, window and confirmation token; follow the rescan instructions for expired or used tokens. Do not share tokens or use another connector's QR to bypass validation.

## CI-03 — No-show and late arrival

At or after the cutoff, a CONFIRMED booking without check-in is recorded as CANCELLED with reason NO_SHOW. An ordinary no-show receives a **0%** refund. Late arrival does not move `endAt`, extend purchased duration or automatically grant a refund for unused time.

A no-show does not remove the right to request verification of station failure. If the Owner accepts responsibility or Admin grants a refund in the exact escalated case, and refund-core receipt/payment requirements are met, a full-package refund obligation may be established; no duplicate refunds. A ticket or Staff finding alone does not grant a refund.

## CI-04 — Ending a session and incidents

CHECKED_IN/CHARGING bookings are outside voluntary cancellation. Ending early does not automatically create a refund for unused minutes. Releasing availability does not promise resale of the final 15 minutes; new bookings still follow current availability, lead-time and time-step requirements.

For an incident during a session, report it and safely resolve the session through the Incident/resolve-session flow before a financial decision on a terminal booking. Stopping a session, recording an Incident or closing a ticket is not a refund decision. A substantiated station failure qualifies for **100% of the package price**, including mid-session, subject to responsibility and valid collection requirements; execution outcome is tracked separately.

## CI-05 — Current configuration and existing bookings

Pre-booking information uses current policy. An existing booking's check-in cutoff, price and deadlines use that booking's committed data. Configuration changes do not extend old bookings. When data cannot be loaded, the app/chatbot must state that information is missing, without inventing deadlines or refund entitlement.

## Sources and navigation

- glossary-and-business-rules-ssot: BR-BOK-04/05/06/07, BR-PAY-07/08.
- 06-bkg-002-system-config-and-booking-policy: configuration and snapshots.
- 37-bkg-056-station-failure-cancellation-and-refund-alignment.vi: station failure.
- check-in-and-no-show.vi: Vietnamese equivalent at the same revision.$document$,
'bkg067-2026-10-09-r1','en');

-- Source: docs/ChargeOps/Knowledge/Published/station-failure-and-responsibility-en-version.md
INSERT INTO chargeops_legal_seed VALUES (
'station-incident-and-support-policy-en',
'OPERATIONAL_REGULATION',
'ALL',
'Station failure and responsibility',
'ChargeOps Booking v4.9',
'Booking v4.9 rules; Simulator, snapshots and API-authorized actions.',
$document$# Station failure and Owner, Staff, Admin responsibility

Content revision: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9 with Simulator finance. This explains general rules; authorized APIs provide permissions and state for a particular case.

## SF-01 — Evidence and operational safety

Tickets, Incidents and findings record information/technical conclusions for incident handling. They do not independently establish refund obligations or prove payment. For an active session, safely resolve it through Incident/resolve-session before a financial decision on a terminal booking. A booking without check-in uses the appropriate cancellation command; chatbot content does not change its state.

## SF-02 — Staff permissions

Staff with an ACTIVE assignment to the correct station may handle operations and record findings within the permitted ticket scope/round. Staff cannot decide financial responsibility, grant refunds or retry Simulator refunds. A STATION_FAILURE finding is evidence, rather than a refund decision.

## SF-03 — Owner permissions and escalation

The Owner within the station/booking-snapshot scope may accept station-failure responsibility when API conditions allow it. The Owner may proactively accept responsibility while a case is escalated, without waiting for Admin adjudication. Station technical findings may be locked during escalation; this does not remove the separate responsibility command.

Do not infer financial permissions from current station ownership or handler status: the backend checks scope, snapshots and state. The Owner may retry only when the system allows it; accepting responsibility does not guarantee every attempt succeeds.

## SF-04 — Admin permissions

Admin reviews financial policy in the **exact active escalated case**. An additional Admin finding remains evidence; a financial grant is a separate command with authorization and version checks. A substantiated grant establishes a full-package refund obligation even if the Owner disagrees; insufficient evidence does not create a new refund or cancel an existing obligation.

The demo has no platform-wide Admin refund queue, Manual Record of transfers or Owner payouts. Handler routing, RETURN_TO_STATION and CLOSE_SUPPORT_CASE are separate support actions; they do not replace a financial decision or refund outcome.

## SF-05 — Refund scope and execution state

Valid station-failure responsibility satisfying refund-core payment/receipt requirements qualifies for **100% of the package price**, including mid-session or after a no-show. Do not refund only remaining time or duplicate amounts already refunded. Excess/UNAPPLIED receipts are reconciled separately, without automatically belonging to the package-refund obligation.

A PENDING obligation does not prove success; a FAILED attempt leaves the obligation PENDING. Track the Simulator outcome and retry only when guards permit. Closing a ticket is not SUCCEEDED; the Simulator does not prove a real bank transaction.

## Sources and navigation

- 34-owner-simulator-finance-policy-and-root-refactor.vi: actors and finance scope.
- 37-bkg-056-station-failure-cancellation-and-refund-alignment.vi: station-failure responsibility.
- 41-bkg-057-admin-refund-policy-backend-checkpoint.vi: review in the exact case.
- 53-bkg-075-dispatch-and-owner-retry-verification.vi: retry and unresolved attempts.
- station-failure-and-responsibility.vi: Vietnamese equivalent at the same revision.$document$,
'bkg067-2026-10-09-r1','en');

-- Source: docs/ChargeOps/Knowledge/Published/refund-attempts-and-owner-ledger.vi.md
INSERT INTO chargeops_legal_seed VALUES (
'refund-attempts-and-owner-ledger-policy',
'OPERATIONAL_REGULATION',
'ALL',
'Nghĩa vụ hoàn, thử lại và sổ Owner',
'ChargeOps Booking v4.9',
'Quy tắc Booking v4.9; Simulator, snapshot và quyền thao tác theo API.',
$document$# Nghĩa vụ hoàn, thử lại và sổ đối soát Owner

Phiên bản nội dung: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9. Thu tiền và hoàn trong demo dùng Simulator; không có payout, ví rút tiền hoặc xác nhận ngân hàng thật.

## RL-01 — Nghĩa vụ khác lần thực thi

Refund là nghĩa vụ hoàn đủ giá gói theo policy/decision hợp lệ, có trạng thái PENDING hoặc SUCCEEDED. RefundAttempt là một lần thực thi với trạng thái STARTED, SUCCEEDED hoặc FAILED, giữ audit và idempotency. FAILED không xóa nghĩa vụ PENDING. Hủy booking, nhận trách nhiệm, grant hoặc đóng ticket thành công chưa chứng minh hoàn SUCCEEDED.

## RL-02 — Lần đầu và thử lại

Refund core có thể phát attempt đầu sau commit theo policy đã duyệt. Khi lần trước FAILED đã xác định kết quả, Owner đúng scope có thể retry nếu guard hiện hành cho phép. Không hứa retry tự động. Staff không retry; Admin không dùng queue hoàn toàn nền tảng hoặc Manual Record để ghi tiền đã chuyển.

Nếu attempt trước còn STARTED hoặc chưa rõ kết quả, không phát attempt mới để tránh hoàn trùng. Đọc trạng thái/khả năng thao tác từ API có quyền, theo hướng dẫn vận hành để đối soát kết quả cũ. Không đổi trạng thái bằng tay hoặc dùng idempotency key mới để vượt guard.

## RL-03 — Điều kiện receipt và chống hoàn trùng

Nghĩa vụ hoàn gói dựa trên payment/receipt hợp lệ đã áp dụng cho booking; lệnh có thể trả NOT_READY khi căn cứ chưa sẵn sàng. Khoản thiếu/thừa/đến muộn/không khớp ở UNAPPLIED không tự tạo khoản hoàn gói và không tự khôi phục booking. Khoản dư cần đối soát riêng; không cam kết tự trả ngân hàng.

Lỗi trạm hợp lệ hoàn 100% giá gói kể cả giữa phiên hoặc sau no-show, không hoàn lại phần đã SUCCEEDED. Đồng thời hoặc replay không được tạo khoản hoàn trùng; tuân thủ version/idempotency và điều kiện máy chủ trả về.

## RL-04 — Sổ Owner và cách đọc số liệu

Sổ đối soát Owner ghi thu tiền gói hợp lệ và hoàn Simulator theo phạm vi tài chính được cấp. Gross là tổng receipt APPLIED khớp giá gói, không gồm khoản UNAPPLIED. Refund đã SUCCEEDED mới giảm net; nghĩa vụ PENDING hiển thị riêng, không coi đã chuyển tiền. Net = gross - refunds SUCCEEDED trong cùng phạm vi/bộ lọc báo cáo.

Sổ này không là số dư ví, tiền có thể rút hoặc payout. Không cộng lịch sử attempt làm nhiều lần hoàn, không coi FAILED là đã trả. Booking dùng snapshot tài chính/Owner đã chốt; đổi chủ trạm không tự chuyển quyền và số liệu lịch sử sang Owner mới.

## RL-05 — Giới hạn tư vấn

FAQ giải thích trạng thái và quy tắc, không xác định số tiền hay quyền retry của đơn cụ thể khi thiếu API có quyền. Luôn kiểm tra obligation, attempt, căn cứ receipt và trạng thái mới nhất trước thao tác. Kết quả Simulator SUCCEEDED chỉ chứng minh hoàn mô phỏng; không báo ngân hàng đã nhận.

## Căn cứ và điều hướng

- 34-owner-simulator-finance-policy-and-root-refactor.vi: ranh giới Simulator.
- 53-bkg-075-dispatch-and-owner-retry-verification.vi: retry/STARTED.
- 55-bkg-065-owner-ledger-and-refund-full-flow-verification.vi: gross/refund/net.
- glossary-and-business-rules-ssot: BR-PAY-07/08/09.
- refund-attempts-and-owner-ledger-en-version: bản Anh cùng revision.$document$,
'bkg067-2026-10-09-r1','vi');

-- Source: docs/ChargeOps/Knowledge/Published/refund-attempts-and-owner-ledger-en-version.md
INSERT INTO chargeops_legal_seed VALUES (
'refund-attempts-and-owner-ledger-policy-en',
'OPERATIONAL_REGULATION',
'ALL',
'Refund attempts and Owner ledger',
'ChargeOps Booking v4.9',
'Booking v4.9 rules; Simulator, snapshots and API-authorized actions.',
$document$# Refund obligations, retries and the Owner reconciliation ledger

Content revision: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9. Demo collections and refunds use the Simulator; there are no payouts, withdrawable wallets or real bank confirmations.

## RL-01 — Obligation versus execution attempt

A Refund is an obligation to refund the full package under a valid policy/decision, with state PENDING or SUCCEEDED. A RefundAttempt is one execution with state STARTED, SUCCEEDED or FAILED, retaining audit and idempotency. FAILED does not remove the PENDING obligation. Successful cancellation, responsibility acceptance, a grant or ticket closure does not prove a SUCCEEDED refund.

## RL-02 — First attempt and retries

The refund core may dispatch the first attempt after commit under an approved policy. After a previous FAILED attempt has a confirmed outcome, the Owner within scope may retry when current guards allow it. Automatic retries are not promised. Staff cannot retry; Admin does not use a platform-wide refund queue or Manual Record to record transfers.

When the previous attempt is STARTED or its outcome is unknown, do not issue a new attempt that could duplicate a refund. Read state/action availability from an authorized API and follow operational instructions to reconcile the old outcome. Do not manually change state or use a fresh idempotency key to bypass guards.

## RL-03 — Receipt requirements and duplicate prevention

Package-refund obligations depend on valid payment/receipts applied to the booking; commands may return NOT_READY when evidence is not ready. Short, excess, late or unmatched UNAPPLIED receipts do not automatically create package refunds or restore bookings. Excess collections require separate reconciliation, without promising automatic bank returns.

A valid station failure qualifies for 100% of the package price, including mid-session or after no-show, without refunding an amount already SUCCEEDED. Concurrent requests or replays must not duplicate refunds; follow version/idempotency and server conditions.

## RL-04 — Owner ledger and interpreting totals

The Owner reconciliation ledger records valid package collections and Simulator refunds within the authorized financial scope. Gross is the total of APPLIED receipts matching package prices, excluding UNAPPLIED collections. Only SUCCEEDED refunds reduce net; PENDING obligations are shown separately and must not be treated as transferred money. Net = gross - SUCCEEDED refunds within the same report scope/filters.

This ledger is not a wallet balance, withdrawable money or a payout. Do not count attempt history as multiple refunds or treat FAILED as paid. Bookings use their committed financial/Owner snapshots; station ownership changes do not automatically transfer historical permissions or totals to the new Owner.

## RL-05 — Advice boundaries

FAQ explains states and rules; it cannot determine a particular booking's amount or retry permission without authorized API data. Check the obligation, attempt, receipt evidence and latest state before acting. Simulator SUCCEEDED proves a simulated refund only; do not report that a bank received it.

## Sources and navigation

- 34-owner-simulator-finance-policy-and-root-refactor.vi: Simulator boundary.
- 53-bkg-075-dispatch-and-owner-retry-verification.vi: retries/STARTED.
- 55-bkg-065-owner-ledger-and-refund-full-flow-verification.vi: gross/refund/net.
- glossary-and-business-rules-ssot: BR-PAY-07/08/09.
- refund-attempts-and-owner-ledger.vi: Vietnamese equivalent at the same revision.$document$,
'bkg067-2026-10-09-r1','en');

-- Source: docs/ChargeOps/Knowledge/Published/faq-and-glossary.vi.md
INSERT INTO chargeops_legal_seed VALUES (
'booking-faq-and-glossary',
'OPERATIONAL_REGULATION',
'ALL',
'FAQ và thuật ngữ Booking',
'ChargeOps Booking v4.9',
'Quy tắc Booking v4.9; Simulator, snapshot và quyền thao tác theo API.',
$document$# FAQ và thuật ngữ Booking

Phiên bản nội dung: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9, phạm vi Simulator. Ví dụ dùng cấu hình mặc định; booking cụ thể theo giá/trạng thái/deadline máy chủ. FAQ chỉ giải thích, không thực hiện lệnh hoặc quyết định tiền.

## FG-01 — Hai mốc 10 phút có giống nhau?

Không. Hold mặc định 10 phút từ lúc tạo booking; retry thanh toán không kéo dài hold. Grace mặc định 10 phút từ lần xác nhận payment hợp lệ đầu tiên; deadline là `min(paymentConfirmedAt + graceMinutes, startAt)` và được lưu snapshot. Hủy hoàn 100% do đổi ý cần CONFIRMED, chưa check-in và máy chủ xử lý **trước** deadline. Đúng deadline hoàn 0%. Config mới/webhook lặp không mở grace mới.

## FG-02 — Tôi đến trễ hoặc kết thúc sớm có được hoàn phần chưa dùng?

Không tự động. Cửa sổ check-in là `[startAt, checkInDeadline)`, mặc định cutoff `endAt - 15 phút`. Phiên 14:00–15:00 đóng check-in đúng 14:45. No-show thông thường hoàn 0%; đến muộn không lùi giờ kết thúc, kết thúc sớm không tạo hoàn theo phút. Lỗi trạm đủ căn cứ và điều kiện receipt/payment áp dụng riêng: hoàn 100% giá gói kể cả giữa phiên/sau no-show, không hoàn trùng.

## FG-03 — Staff đã ghi lỗi trạm, hoặc ticket đã đóng, tiền đã hoàn chưa?

Chưa thể kết luận. Finding là chứng cứ kỹ thuật; Owner nhận trách nhiệm đúng scope hoặc Admin grant đúng case escalated là quyết định tài chính riêng. Staff không quyết định/retry hoàn. Owner vẫn có thể nhận trách nhiệm khi case escalated. Nghĩa vụ PENDING chưa là SUCCEEDED; attempt FAILED để nghĩa vụ PENDING, STARTED/chưa rõ kết quả phải đối soát trước attempt mới. Đóng ticket không chứng minh quyền hoặc kết quả hoàn.

## FG-04 — Vì sao phải xác nhận lại hủy? Trang payment thành công có đủ không?

Máy chủ có thể xử lý sau deadline nên số tiền hoàn khác preview. `409 BKG_CANCELLATION_CHANGED` nghĩa là booking **chưa bị hủy**; đọc preview mới và đồng ý lại nếu tiếp tục. Không tự replay consent cũ. Trang payment thành công chỉ là hiển thị; cần booking/payment được máy chủ xác nhận. Receipt đến muộn, thiếu/thừa/không khớp ở UNAPPLIED không tự khôi phục booking hoặc tạo hoàn gói; đối soát riêng, không hứa tự trả ngân hàng.

## FG-05 — Từ điển Việt–Anh

| Thuật ngữ | English/API | Nghĩa dùng trong ChargeOps |
|---|---|---|
| Giá gói | Package price | Giá thời gian đã chốt; không đặt cọc, không hóa đơn điện năng thực đo |
| Giữ chỗ | Hold | Cửa sổ trả tiền từ lúc tạo booking |
| Ân hạn hủy | Cancellation grace | Cửa sổ hoàn do đổi ý từ lần xác nhận payment đầu, cắt tại startAt |
| Hạn hoàn do đổi ý | freeCancellationDeadline | Deadline snapshot; đúng hạn không đủ điều kiện |
| Hạn check-in | checkInDeadline | Cutoff loại trừ; mặc định endAt trừ 15 phút |
| Kết luận kỹ thuật | Finding | Chứng cứ; không là financial decision |
| Nhận trách nhiệm / cấp hoàn | Responsibility acceptance / financial grant | Quyết định đúng actor/scope; không chứng minh attempt thành công |
| Nghĩa vụ hoàn | Refund obligation | PENDING hoặc SUCCEEDED |
| Lần thực thi hoàn | RefundAttempt | STARTED, SUCCEEDED hoặc FAILED |
| Khoản chưa áp dụng | UNAPPLIED receipt | Thu tiền chưa áp dụng vào gói; đối soát riêng |
| Sổ đối soát chủ trạm | Owner reconciliation ledger | Gross, hoàn SUCCEEDED và net; PENDING riêng; không là ví/payout |
| Mô phỏng tài chính | Simulator | Không chứng minh chuyển ngân hàng thật |
| Revision nội dung | Content revision | Phiên bản biên tập, không dùng làm acceptedPolicyVersion |
| Phiên bản policy | policyVersion | Cấu hình hiện hành từ API; đơn cũ giữ snapshot |

## FG-06 — Giới hạn trợ lý

Thiếu nguồn, nguồn khác revision hoặc mâu thuẫn phải nêu chưa đủ căn cứ. Trả lời theo locale người hỏi và dẫn đúng rule; không trộn bản dịch cũ. Không tự tính quyền/số tiền đơn cụ thể, gọi refund/hủy/retry hoặc hứa tiền ngân hàng đã về. Số liệu cá nhân cần API có quyền, không suy từ FAQ. Demo không có payout, ví/rút tiền, Admin queue hoàn toàn nền tảng, Manual Record, phí booking hoặc hoa hồng; license B2B là khoản riêng.

## Căn cứ và điều hướng

- booking-and-payment.vi · cancellation-and-refunds.vi.
- check-in-and-no-show.vi · station-failure-and-responsibility.vi.
- refund-attempts-and-owner-ledger.vi · glossary-and-business-rules-ssot.
- faq-and-glossary-en-version: bản Anh cùng revision.$document$,
'bkg067-2026-10-09-r1','vi');

-- Source: docs/ChargeOps/Knowledge/Published/faq-and-glossary-en-version.md
INSERT INTO chargeops_legal_seed VALUES (
'booking-faq-and-glossary-en',
'OPERATIONAL_REGULATION',
'ALL',
'Booking FAQ and glossary',
'ChargeOps Booking v4.9',
'Booking v4.9 rules; Simulator, snapshots and API-authorized actions.',
$document$# Booking FAQ and glossary

Content revision: **bkg067-2026-10-09-r1**. ChargeOps Booking v4.9, Simulator scope. Examples use default configuration; each booking follows server prices/states/deadlines. FAQ explains rules without executing commands or deciding money.

## FG-01 — Are the two 10-minute windows the same?

No. The default hold is 10 minutes from booking creation; payment retries do not extend it. Default grace is 10 minutes from the first valid payment confirmation; its deadline is `min(paymentConfirmedAt + graceMinutes, startAt)` and is snapshotted. A 100% change-of-mind refund requires CONFIRMED, no check-in and server processing **before** the deadline. Exactly at the deadline, the refund is 0%. New configuration/replayed webhooks do not open new grace.

## FG-02 — Do late arrival or early ending refund unused time?

Not automatically. The check-in window is `[startAt, checkInDeadline)`, with default cutoff `endAt - 15 minutes`. A 14:00–15:00 session closes check-in exactly at 14:45. Ordinary no-shows receive 0%; late arrival does not move the end time, and ending early does not create a per-minute refund. A substantiated station failure meeting receipt/payment requirements is separate: 100% of the package price, including mid-session/after no-show, without duplicate refunds.

## FG-03 — Staff recorded station failure, or the ticket closed. Is the refund paid?

That cannot be concluded. A finding is technical evidence; Owner responsibility acceptance within scope or an Admin grant in the exact escalated case is a separate financial decision. Staff cannot decide/retry refunds. The Owner may still accept responsibility during escalation. A PENDING obligation is not SUCCEEDED; a FAILED attempt leaves it PENDING, while STARTED/unknown outcomes require reconciliation before another attempt. Ticket closure does not prove entitlement or execution success.

## FG-04 — Why reconfirm cancellation? Is the payment success page sufficient?

The server may process after the deadline, changing the refund amount from the preview. `409 BKG_CANCELLATION_CHANGED` means the booking **has not been cancelled**; read the new preview and consent again if continuing. Do not replay old consent automatically. A payment success page is only a display; server-confirmed booking/payment state is required. Late, short, excess or unmatched UNAPPLIED receipts do not automatically restore bookings or create package refunds; reconcile separately without promising automatic bank returns.

## FG-05 — Vietnamese–English glossary

| Vietnamese term | English/API | Meaning in ChargeOps |
|---|---|---|
| Giá gói | Package price | Committed time-package price; no deposit or metered-energy bill |
| Giữ chỗ | Hold | Payment window starting at booking creation |
| Ân hạn hủy | Cancellation grace | Change-of-mind refund window from first payment confirmation, capped at startAt |
| Hạn hoàn do đổi ý | freeCancellationDeadline | Snapshotted deadline; exactly at it is ineligible |
| Hạn check-in | checkInDeadline | Exclusive cutoff; default endAt minus 15 minutes |
| Kết luận kỹ thuật | Finding | Evidence, rather than a financial decision |
| Nhận trách nhiệm / cấp hoàn | Responsibility acceptance / financial grant | Decision by the proper actor/scope; does not prove a successful attempt |
| Nghĩa vụ hoàn | Refund obligation | PENDING or SUCCEEDED |
| Lần thực thi hoàn | RefundAttempt | STARTED, SUCCEEDED or FAILED |
| Khoản chưa áp dụng | UNAPPLIED receipt | Collection not applied to the package; reconcile separately |
| Sổ đối soát chủ trạm | Owner reconciliation ledger | Gross, SUCCEEDED refunds and net; PENDING separately; no wallet/payout |
| Mô phỏng tài chính | Simulator | Does not prove real bank transfers |
| Revision nội dung | Content revision | Editorial version, not acceptedPolicyVersion |
| Phiên bản policy | policyVersion | Current API configuration; old bookings retain snapshots |

## FG-06 — Assistant boundaries

For missing sources, different revisions or conflicting sources, state that evidence is insufficient. Answer in the questioner's locale with the correct rule citation; do not mix outdated translations. Do not calculate a specific booking's entitlement/amount, call refund/cancel/retry or promise bank arrival. Personal data requires an authorized API, rather than inference from FAQ. The demo has no payouts, withdrawable wallets, platform-wide Admin refund queue, Manual Record, booking fees or commissions; B2B license fees are separate.

## Sources and navigation

- booking-and-payment-en-version · cancellation-and-refunds-en-version.
- check-in-and-no-show-en-version · station-failure-and-responsibility-en-version.
- refund-attempts-and-owner-ledger-en-version · glossary-and-business-rules-ssot.
- faq-and-glossary.vi: Vietnamese equivalent at the same revision.$document$,
'bkg067-2026-10-09-r1','en');

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
