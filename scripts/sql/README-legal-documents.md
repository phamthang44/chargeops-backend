# Bộ văn bản nghiệp vụ ChargeOps

`seed-legal-documents.sql` là nguồn nội dung seed chỉnh sửa được, nằm ngoài
`classpath:db/migration`. Flyway chỉ tạo cấu trúc bảng bằng V25; ứng dụng không
tự chạy script này khi khởi động. V25 được tách khi chưa chạy trên database nào
theo xác nhận của chủ dự án. Sau khi V25 đã chạy, giữ nguyên file migration.

## Chạy script

1. Cho backend/Flyway áp dụng V25 trên database cần dùng.
2. Mở `scripts/sql/seed-legal-documents.sql` bằng UTF-8, đọc nội dung cần công bố.
3. Chạy **toàn bộ file** trong một SQL editor/session mới của PostgreSQL, hoặc
   dùng lệnh sau từ thư mục gốc backend (thay host, user và database phù hợp):

```powershell
psql -X -h localhost -p 5432 -U postgres -d chargeops -v ON_ERROR_STOP=1 -f scripts/sql/seed-legal-documents.sql
```

Script tự mở transaction và commit khi mọi câu lệnh thành công. Nếu SQL editor
dừng giữa chừng vì lỗi, chạy `ROLLBACK;` rồi sửa nguyên nhân và chạy lại toàn bộ.
Không chạy các câu lệnh riêng lẻ. Script không đổi `system_configs`, không sửa
`flyway_schema_history` và không thực hiện Flyway repair.

## Hành vi khi chạy lại

- Upsert theo `slug`: cập nhật các trường nội dung, loại, đối tượng, phiên bản,
  ngôn ngữ của 15 tài liệu trong danh mục. **Nội dung đã sửa qua Admin ở cùng
  slug sẽ bị ghi đè bằng nội dung trong script.**
- Không tạo trùng slug; không thay ID, thời điểm/người tạo, ngày hiệu lực,
  trạng thái `is_active` hoặc `deleted_at` của bản ghi đã có.
- Bản ghi mới được tạo ở trạng thái active, có hiệu lực tại lúc chạy. Bản ghi
  cũ đang ẩn hoặc xóa mềm không được tự khôi phục.
- Không xóa tài liệu ngoài danh mục. Khi nội dung không đổi, không thay
  `updated_at`; khi cập nhật, `updated_by = NULL` biểu thị thao tác hệ thống.
- Kết quả trả số tài liệu, số bản ghi thêm/sửa và trạng thái công bố để kiểm tra.
  Ghi dữ liệu được khóa trong transaction; đọc vẫn được phép. Nếu không lấy
  được khóa trong 10 giây, script báo lỗi để chạy lại khi phù hợp.

## Danh mục 15 tài liệu

| Slug | Nội dung | Đối tượng |
|---|---|---|
| terms-of-service | Điều khoản dịch vụ | ALL |
| privacy-policy | Dữ liệu và quyền riêng tư | ALL |
| station-owner-license-agreement | Thỏa thuận thuê bao chủ trạm | OWNER |
| operational-regulations | Quy chế vận hành chung | ALL |
| booking-time-and-availability | Thời gian đặt và lịch khả dụng | ALL |
| time-package-pricing-policy | Công thức, giá ước tính và giá đã chốt | ALL |
| cancellation-and-refund-policy | Hủy, ân hạn và hoàn tiền | ALL |
| qr-check-in-and-no-show-policy | QR, đến muộn, vắng mặt | ALL |
| payment-reconciliation-policy | Thu tiền, thiếu/thừa/đến muộn | ALL |
| station-incident-and-support-policy | Ticket, lỗi trạm, tranh chấp | ALL |
| station-discovery-and-eligibility-policy | Điều kiện tìm kiếm và đặt chỗ | ALL |
| license-lifecycle-and-renewal-policy | Hiệu lực, gia hạn và đình chỉ | OWNER |
| station-staff-access-policy | Phân công và quyền nhân viên | OWNER |
| station-equipment-operation-policy | Trụ/cổng, công suất và bảo trì | OWNER |
| owner-payout-and-adjustment-policy | Chi trả và điều chỉnh chủ trạm | OWNER |

Các chuyên đề vận hành dùng `OPERATIONAL_REGULATION`; tài liệu vòng đời license
dùng `LICENSE_AGREEMENT`. Giữ `OWNER` theo enum Java hiện tại. `doc_type` là nhóm
tài liệu, không phải định danh: nhiều tài liệu được dùng chung loại, phiên bản
và ngôn ngữ. V25 giữ UNIQUE(slug), bỏ ràng buộc cũ trên type/version/locale.
Không cần thêm enum hoặc sửa API để đọc danh mục này.

## Căn cứ và cách cập nhật

Mỗi tài liệu có mục **Căn cứ nghiệp vụ**; comment `-- Source:` ghi đường dẫn
Vault tương ứng và các mục đối chiếu. Nội dung được biên soạn theo chuyên đề,
không sao chép các kế hoạch code, dữ liệu fixture hoặc tài liệu superseded như
một cam kết tính năng đã chạy. Các phần Booking–Payment và template kiểm soát
license được ghi rõ phạm vi thiết kế.

Vault có chỗ diễn đạt khác nhau: tài liệu tổng quan phần cứng nói tổng công suất,
nhưng T17 và API chi tiết kiểm tra từng cổng với công suất trụ; danh mục theo T17.
Thiết kế Booking v4.9 cũng được ưu tiên trước các ghi chú QR tĩnh/no-show cũ.

Để sửa/bổ sung văn bản, chỉnh **script thủ công**, giữ slug ổn định, thêm mục nguồn
và cập nhật bảng danh mục này. Giữ các giá trị enum hợp lệ. `4.9.0` trong seed là
phiên bản nền nghiệp vụ; bảng hiện tại lưu một bản hiện hành cho mỗi slug, không
cung cấp lịch sử phiên bản bất biến. Nếu sửa quy tắc định lượng, phải đối chiếu
nguồn `system_configs` và policy thực thi; đổi câu chữ không tự thay đổi nghiệp vụ.

Script dừng nếu chưa có bảng hoặc còn ràng buộc type/version/locale cũ. Với môi
trường đã từng áp dụng một V25 khác, cần giữ nguyên bản migration đã chạy và tạo
migration cấu trúc mới; không sửa checksum để bỏ qua khác biệt.

## Kiểm chứng ngày 08/09/2026

Đã chạy V25 và script bằng PostgreSQL 17 trong cluster tạm độc lập:

- Thiếu bảng: script dừng trước khi ghi dữ liệu.
- V25 tạo bảng rỗng; chạy seed lần đầu tạo đủ 15 văn bản.
- Chạy lại không đổi bất kỳ dữ liệu, ID hoặc timestamp nào.
- Sửa nội dung rồi chạy lại: nội dung được đồng bộ, ID/người tạo/ngày hiệu lực
  và trạng thái ẩn/xóa mềm được giữ; không đụng tài liệu ngoài danh mục.
- Kiểm tra tĩnh: 15 slug duy nhất, enum hợp lệ qua ràng buộc staging, UTF-8,
  tiêu đề, phạm vi và căn cứ nghiệp vụ đầy đủ; mọi đường dẫn nguồn đều tồn tại.

Cluster tạm đã được dừng. Chưa chạy script trên database dự án hoặc kiểm tra
toàn bộ backend/API; các phép thử trên tập trung vào schema và dữ liệu SQL.
