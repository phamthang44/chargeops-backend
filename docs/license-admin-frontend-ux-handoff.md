# Frontend Handoff — Admin License List, Detail và History

> Cập nhật: 2026-08-18  
> Đối tượng: Frontend team, Backend team, UI/UX reviewer  
> Phạm vi: màn hình quản trị License, mã hiển thị License, list/detail, lịch sử và các action lifecycle.  
> Lưu ý: tài liệu này mô tả thiết kế mục tiêu. Những endpoint được đánh dấu **chưa implement** không được xem là API contract đang hoạt động.

## 1. Kết luận thiết kế

Màn hình Admin License nên đi theo mô hình:

```text
Operational summary table
        ↓ click row / Xem chi tiết
License detail drawer
        ├── Tổng quan License
        ├── Action theo trạng thái
        ├── Nhật ký trạng thái của License
        └── Các kỳ License của station
```

Các quyết định chính:

1. Bổ sung `licenseCode` dạng dễ đọc và lưu thật trong database.
2. UUID `id` tiếp tục là technical identifier dùng trong URL, foreign key và mutation API.
3. Table chỉ hiển thị thông tin cần thiết để admin scan và xử lý nhanh.
4. Thông tin đầy đủ được đưa vào detail drawer.
5. Phân biệt rõ hai loại lịch sử:
   - Status-event history của một License.
   - Các kỳ License khác nhau của một station.
6. Suspend, reactivate và cancel nằm trong action menu/detail drawer và luôn có confirmation.
7. Search phải được backend hỗ trợ khi list sử dụng server-side pagination.

## 2. Vấn đề của thiết kế hiện tại

Frontend mock đang sử dụng các ID như:

```text
lic-001
lic-002
lic-003
```

Sau đó hiển thị trực tiếp `license.id` dưới nhãn **Mã License**.

Backend hiện chỉ có:

```text
id: UUID
```

Khi kết nối API thật, giao diện sẽ hiển thị UUID dài như:

```text
71c9fc68-3bb8-49e1-a27e-c301ad3519b0
```

UUID phù hợp cho kỹ thuật nhưng không phù hợp để admin đọc, ghi nhớ, trao đổi hoặc tìm kiếm thủ công.

Ngoài ra, table hiện đang đưa gần như toàn bộ thông tin License lên cùng một hàng:

- ID.
- Station.
- Owner.
- Plan.
- Cả khoảng `startAt → expiresAt`.
- Fee.
- Status.
- Renew và lifecycle actions.

Cách này làm table rộng, khó scan và trùng nhiều thông tin nên thuộc về detail.

## 3. Human-readable `licenseCode`

### 3.1 Mô hình đề xuất

```text
id          = UUID          — technical identifier
licenseCode = LIC-000001    — business/display identifier
```

Ví dụ:

```text
LIC-001000
LIC-001001
LIC-001002
```

Quy tắc:

- Unique toàn hệ thống.
- Immutable sau khi tạo.
- Không tái sử dụng code của License đã xóa/hết hạn.
- Không dùng code thay cho UUID trong quan hệ database.
- Không reset sequence theo năm.
- Frontend hiển thị uppercase.

### 3.2 Không derive code từ UUID

Không sử dụng cách:

```text
LIC-{8 ký tự đầu của UUID}
```

Lý do:

- Có rủi ro collision nếu cắt ngắn UUID.
- Khó index và search chính xác.
- Format phụ thuộc implementation UUID.
- Không phải business identifier ổn định.

### 3.3 Backend target

Entity target:

```java
@Column(
    name = "license_code",
    nullable = false,
    unique = true,
    updatable = false,
    length = 20
)
private String licenseCode;
```

Migration target:

```sql
CREATE SEQUENCE license_code_seq START WITH 1000;

ALTER TABLE licenses
ADD COLUMN license_code varchar(20);

-- Backfill dữ liệu cũ trước khi SET NOT NULL.

ALTER TABLE licenses
ALTER COLUMN license_code SET NOT NULL;

CREATE UNIQUE INDEX ux_licenses_license_code
ON licenses (license_code);
```

Format server-side:

```java
"LIC-%06d".formatted(sequence)
```

Backend hiện **chưa implement** `licenseCode`. Frontend có thể chuẩn bị type nhưng không được tự tạo một business code giả khi dùng API thật.

## 4. Nguyên tắc sử dụng `id` và `licenseCode`

| Trường | Mục đích |
|---|---|
| `id` | URL, API mutation, foreign key, React row key |
| `licenseCode` | Hiển thị, copy, tìm kiếm, trao đổi với admin/owner |

Ví dụ URL:

```http
GET /api/v1/admin/licenses/71c9fc68-3bb8-49e1-a27e-c301ad3519b0
```

Ví dụ UI:

```text
Mã License: LIC-001024
```

Frontend phải dùng:

```tsx
key={license.id}
```

Không dùng:

```tsx
key={license.licenseCode}
```

`licenseCode` dù unique vẫn là business/display field; `id` mới là identity kỹ thuật của resource.

## 5. Information architecture đề xuất

### 5.1 Admin License table

Table là operational summary. Mục tiêu là giúp admin trả lời nhanh:

- License nào cần chú ý?
- Thuộc station nào?
- Đang ở trạng thái gì?
- Khi nào hết hạn?
- Có action nào hợp lệ?

Các cột đề xuất:

| Cột | Nội dung |
|---|---|
| Mã License | `licenseCode`, có copy action |
| Trạm | Station name + `stationCode` ở dòng phụ |
| Chủ sở hữu | Owner display name |
| Gói | Tháng/Năm |
| Hết hạn | `expiresAt` + derived “Còn N ngày” |
| Trạng thái | Domain status badge |
| Thao tác | Primary action có điều kiện + overflow menu |

Thông tin không cần nằm trong table chính:

- UUID đầy đủ.
- `startAt`.
- Toàn bộ khoảng `startAt → expiresAt`.
- `createdAt`, `createdBy`.
- Status reason.
- Status-event history.
- `feeAmount` nếu table đã có plan và cần giảm độ rộng.

`feeAmount` vẫn được backend lưu và trả về làm price snapshot. Nó có thể hiển thị trong detail drawer. Nếu product muốn giữ fee trên desktop table, xem đây là cột secondary và cho phép ẩn khi viewport nhỏ.

### 5.2 Row behavior

Mỗi row hỗ trợ:

- Click row hoặc chọn **Xem chi tiết** để mở detail drawer.
- Copy `licenseCode` mà không mở drawer.
- Không trigger row click khi người dùng đang mở action menu.
- Không thực hiện destructive mutation trực tiếp khi click menu item.

### 5.3 Responsive priority

Thứ tự giữ cột khi viewport nhỏ:

1. Mã License.
2. Station.
3. Expiry.
4. Status.
5. Actions.

Owner, plan và fee có thể chuyển vào secondary text hoặc detail drawer.

## 6. License detail drawer

Detail nên dùng drawer bên phải thay vì một modal nhỏ, vì nội dung có cả overview, action và history dài.

### 6.1 Header

- `licenseCode`.
- Status badge.
- Station name + `stationCode`.
- Nút copy code.
- Close action.

### 6.2 Overview

- License UUID, chỉ hiển thị ở technical details/copy area.
- Owner display name.
- Plan.
- Fee snapshot.
- `startAt`.
- `expiresAt`.
- Thời gian còn lại.
- `createdAt`.
- Người ghi nhận ban đầu nếu API có dữ liệu.

### 6.3 Action area

Action được dựng từ domain status, không hard-code theo màn hình:

| Status | Action hợp lệ |
|---|---|
| `PENDING` | Activate nếu đang trong effective period; Cancel |
| `ACTIVE` | Suspend; Cancel; Renew khi sắp hết hạn |
| `SUSPENDED` | Reactivate nếu còn hạn; Cancel |
| `EXPIRED` | Ghi nhận kỳ License mới/Renew |
| `CANCELLED` | Không transition; có thể ghi nhận subscription mới |

Renew không update row hiện tại. Renew tạo một License row mới.

### 6.4 Confirmation

Các mutation cần confirmation modal:

- Suspend: giải thích thời hạn vẫn tiếp tục chạy.
- Reactivate: hiển thị thời gian còn lại.
- Cancel: danger confirmation và nói rõ đây là terminal action.
- Renew: nói rõ tạo row License mới.

Button mutation phải có:

- Pending/loading state.
- Chống double submit.
- Error theo stable backend error code.
- Refetch list, detail và history sau khi thành công.

## 7. Phân biệt hai loại lịch sử

### 7.1 Status-event history của một License

Nguồn dữ liệu:

```text
license_status_events
```

Scope:

```text
licenseId
```

Ví dụ timeline:

```text
ISSUED      SYSTEM/ADMIN A   18/08/2026 09:00
ACTIVATED   ADMIN A          18/08/2026 09:00
SUSPENDED   ADMIN B          25/08/2026 15:30
REACTIVATED ADMIN C          26/08/2026 08:10
EXPIRED     SYSTEM           18/09/2026 09:00
```

Mỗi event cần:

- `eventType`.
- `fromStatus`.
- `toStatus`.
- `reason`.
- `actorType`.
- `performedByName`, nếu actor là user.
- `performedAt`.

### 7.2 Các kỳ License của một station

Nguồn dữ liệu:

```text
licenses WHERE station_id = :stationId
```

Scope:

```text
stationId
```

Ví dụ:

```text
ST-1023
├── LIC-001001 — YEARLY — EXPIRED
├── LIC-001024 — YEARLY — ACTIVE
└── LIC-001109 — MONTHLY — PENDING
```

Đây là subscription-period history, không phải status-event history.

### 7.3 UI target

Detail drawer nên có hai tab:

```text
[ Nhật ký trạng thái ] [ Các kỳ License của trạm ]
```

Không đặt một action mơ hồ tên **Lịch sử trạm** trên License row.

Wording đề xuất:

- `Xem chi tiết` — mở selected License.
- `Nhật ký trạng thái` — timeline của selected License.
- `Các kỳ License của trạm` — các License row thuộc station.

## 8. Search và filter

Frontend hiện có thể filter mock/in-memory. Khi chuyển sang API có pagination, search phải chạy phía backend.

Target request:

```http
GET /api/v1/admin/licenses
    ?search=LIC-001024
    &status=ACTIVE
    &pageNo=1
    &pageSize=20
    &sort=expiresAt,asc
```

Backend nên search trên:

- `licenseCode`.
- `stationCode`.
- Station name.
- Owner display name.
- Owner email nếu được phép hiển thị/tìm kiếm.
- UUID nếu search string parse được UUID.

Frontend requirements:

- Debounce search khoảng 300–500 ms.
- Reset về page 1 khi đổi search/filter.
- URL query state nếu muốn hỗ trợ back/forward và share filter.
- Không filter riêng dữ liệu của page hiện tại rồi gọi đó là global search.

## 9. DTO mục tiêu

### 9.1 Admin License list item

```ts
export interface AdminLicenseListItem {
  id: string;
  licenseCode: string;
  stationId: string;
  stationCode: string;
  stationName: string;
  ownerName: string;
  plan: 'MONTHLY' | 'YEARLY';
  expiresAt: string;
  status: 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'CANCELLED' | 'EXPIRED';
}
```

Có thể bổ sung `feeAmount` và `startAt` nếu product quyết định giữ chúng ở list response, nhưng frontend không nên phụ thuộc vào chúng để render table summary.

### 9.2 Admin License detail

```ts
export interface AdminLicenseDetail extends AdminLicenseListItem {
  feeAmount: number;
  startAt: string;
  createdAt: string;
  recordedByName?: string;
}
```

### 9.3 License status event

```ts
export interface LicenseStatusEventDto {
  id: string;
  eventType:
    | 'ISSUED'
    | 'ACTIVATED'
    | 'SUSPENDED'
    | 'REACTIVATED'
    | 'CANCELLED'
    | 'EXPIRED';
  fromStatus?: LicenseStatus | null;
  toStatus: LicenseStatus;
  reason?: string | null;
  actorType: 'USER' | 'SYSTEM';
  performedByName?: string | null;
  performedAt: string;
}
```

## 10. Target API contract

### 10.1 Đã có một phần

```http
POST /api/v1/stations/{stationId}/licenses
```

Issue cần `stationId` vì đây là thao tác tạo resource mới dưới station.

### 10.2 Target đề xuất — chưa implement đầy đủ

```http
GET /api/v1/admin/licenses
GET /api/v1/admin/licenses/{licenseId}
GET /api/v1/admin/licenses/{licenseId}/status-events

POST /api/v1/admin/licenses/{licenseId}/suspend
POST /api/v1/admin/licenses/{licenseId}/reactivate
POST /api/v1/admin/licenses/{licenseId}/cancel
POST /api/v1/admin/licenses/{licenseId}/renew

GET /api/v1/stations/{stationId}/licenses
```

Ý nghĩa:

| Endpoint | Scope |
|---|---|
| `/admin/licenses` | Global admin list/search/filter |
| `/admin/licenses/{licenseId}` | Một License cụ thể |
| `/status-events` | Audit timeline của một License |
| `/stations/{stationId}/licenses` | Các kỳ License của một station |

Issue sử dụng station URL. Các mutation sau khi License đã tồn tại chỉ cần UUID `licenseId`.

## 11. Mismatch backend/frontend hiện tại

Backend hiện tại:

- `License` chưa có `licenseCode`.
- Admin License list chưa implement hoàn chỉnh.
- License detail chưa implement hoàn chỉnh.
- Chưa có API đọc `LicenseStatusEvent`.
- Suspend controller hiện chưa khớp target URL/action contract.

Frontend hiện tại:

- Hiển thị `license.id` như human-readable code.
- Search mock bằng `l.id`, station và owner trong dữ liệu đã load.
- Table đang hiển thị gần như full License information.
- `LicenseHistoryDrawer` thực chất hiển thị các License row theo station.
- Chưa hiển thị timeline từ `license_status_events`.
- Action UI đã có suspend/reactivate/cancel nhưng không được xem là integrated cho tới khi backend endpoint tương ứng hoàn tất.

## 12. Acceptance criteria

### License code

- UI không hiển thị raw UUID làm “Mã License”.
- `licenseCode` có format ổn định và copy được.
- UUID vẫn được dùng làm React key và API path parameter.
- Search theo exact/partial `licenseCode` hoạt động trên toàn dataset.

### Table

- Admin scan được code, station, expiry, status và action mà không cần horizontal overload trên desktop chuẩn.
- Table không chứa toàn bộ audit/detail data.
- Expiring-soon là derived UI indicator; status vẫn là `ACTIVE`.
- Row action được dựng đúng theo domain status.

### Detail

- Click row hoặc “Xem chi tiết” mở đúng License theo UUID.
- Drawer hiển thị đầy đủ plan, fee snapshot, start/expiry và station/owner.
- Mutation thành công làm list và detail đồng bộ lại.

### History

- Status timeline lấy theo `licenseId`.
- Subscription periods lấy theo `stationId`.
- Hai loại history có wording và tab riêng.
- SYSTEM event không hiển thị như một admin user.

### Safety

- Suspend/reactivate/cancel có confirmation.
- Cancel được trình bày là terminal action.
- Mutation có pending state và chống double-submit.
- Optimistic/concurrent conflict yêu cầu refetch và báo người dùng reload dữ liệu.

## 13. Thứ tự triển khai đề xuất

### Backend

1. Thêm migration và field `licenseCode`.
2. Trả `licenseCode` trong issue response.
3. Implement paginated admin License list/search/filter.
4. Implement License detail.
5. Implement status-event read API.
6. Chuẩn hóa suspend/reactivate/cancel/renew endpoints.

### Frontend

1. Thêm `licenseCode` vào API types nhưng feature-gate cho tới khi backend trả field.
2. Đổi table sang operational summary.
3. Tạo License detail drawer.
4. Tách status timeline và station subscription periods.
5. Kết nối server-side pagination/search/filter.
6. Kết nối lifecycle actions sau khi backend endpoints hoàn tất.

## 14. Source files đã đối chiếu

Backend:

- `src/main/java/com/thang/chargeops/station/entity/License.java`
- `src/main/java/com/thang/chargeops/station/entity/LicenseStatusEvent.java`
- `src/main/java/com/thang/chargeops/station/controller/AdminLicenseController.java`
- `src/main/java/com/thang/chargeops/station/repository/LicenseRepository.java`
- `src/main/java/com/thang/chargeops/station/repository/LicenseStatusEventRepository.java`
- `src/main/java/com/thang/chargeops/station/dto/station/response/IssueLicenseResponse.java`
- `src/main/java/com/thang/chargeops/station/dto/station/response/DetailLicenseResponse.java`
- `src/main/resources/db/migration/V1__initial_core_schema.sql`
- `src/main/resources/db/migration/V8__add_license_status_events_table.sql`
- `chargeops_erd_v4_1.dbml`

Frontend:

- `chargeops-web/apps/web/src/admin/pages/Licenses.tsx`
- `chargeops-web/apps/web/src/admin/features/licenses/LicenseHistoryDrawer.tsx`
- `chargeops-web/apps/web/src/admin/features/licenses/LicenseActionModal.tsx`
- `chargeops-web/packages/api/src/types.ts`
- `chargeops-web/packages/api/src/services.ts`
- `chargeops-web/packages/api/src/mock/services.ts`

