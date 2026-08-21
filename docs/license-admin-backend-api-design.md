# Backend Design & API Contract Specification: Admin License Module

> **Mục đích tài liệu**: Định nghĩa kiến trúc Backend (Spring Boot 4.1 + JPA Specifications + MapStruct), chuẩn hóa API Contracts, phân tách rõ ràng trách nhiệm Controller (Query vs Command/Lifecycle), và ánh xạ nhu cầu UI Frontend sang DTOs/Entities phía Backend.  
> **Trạng thái 2026-08-21:** Đây là nguồn policy duy nhất cho Admin License,
> bao gồm lifecycle, renewal, scheduler recovery và template Station Licensing
> Control. Nội dung từ `ChargeOps_License_Renew_Review.md` đã được hợp nhất vào
> tài liệu này để tránh hai tài liệu cùng mô tả một rule nhưng bị lệch nhau.  

---

## Mục lục

- [License Glossary — Thuật ngữ cốt lõi](#license-glossary--thuật-ngữ-cốt-lõi)
- [1. Bức tranh tổng quan](#1-bức-tranh-tổng-quan-frontend-cần-những-gì--tại-sao)
- [2. Dynamic JPA Specification](#2-thiết-kế-tìm-kiếm-động-bằng-jpa-specification-licensespecification)
- [3. Controller responsibilities](#3-kiến-trúc-phân-tách-trách-nhiệm-controllers-query--lifecycle)
- [4. API contracts](#4-chi-tiết-api-contracts-frontend--backend)
- [5. MapStruct mapping](#5-cấu-hình-mapstruct-mapper-licenseMapperjava)
- [6. Controller code mẫu](#6-code-controller-mẫu)
- [7. Admin License Lifecycle Policy](#7-admin-license-lifecycle-policy)
- [7.4. Renewal policy (final)](#74-renewal-policy-final)
- [7.5. Station Licensing Control template](#75-station-licensing-control-template)
- [7.6. Cancel period và revoke relationship](#76-cancel-period-và-revoke-relationship)
- [7.7. Pending-renewal activation và recovery](#77-pending-renewal-activation-và-recovery)
- [8. State matrix và checklist](#8-checklist-tự-kiểm-tra--ma-trận-trạng-thái--audit-events)

> Mẹo đọc nhanh: nếu cần hiểu các từ như `effective window`, `PENDING`, `ACTIVE`,
> `renew` trước, đọc [License Glossary](#license-glossary--thuật-ngữ-cốt-lõi).

---

## License Glossary — Thuật ngữ cốt lõi

Phần này là từ điển thống nhất cho các thuật ngữ được dùng trong toàn bộ tài
liệu. Các term có dấu `@` trong code hoặc comment vẫn mang đúng nghĩa dưới đây.

### `effective window` — khoảng thời gian có hiệu lực

Là khoảng thời gian mà License được phép tạo business mới, được biểu diễn bởi:

```text
[startAt, expiresAt)
```

Ký hiệu `[` nghĩa là **bao gồm** `startAt`; ký hiệu `)` nghĩa là **không bao
gồm** `expiresAt`. Vì vậy:

```text
startAt <= now && now < expiresAt
```

Tại đúng thời điểm `now == expiresAt`, License đã hết hiệu lực dù scheduler
chưa kịp đổi cột `status` sang `EXPIRED`.

### `startAt` — thời điểm bắt đầu hiệu lực

Thời điểm License bắt đầu được sử dụng cho business. Trước thời điểm này:

```text
status thường là PENDING
License không effectively active
Station không đủ điều kiện discovery/booking
```

`startAt` không phải thời điểm record được tạo. Với early renew, record có thể
được tạo hôm nay nhưng `startAt` nằm sau ngày hết hạn của kỳ cũ.

### `expiresAt` — thời điểm kết thúc hiệu lực

Thời điểm biên mà License không còn usable. Đây là **exclusive end** của
effective window, không phải “hết vào cuối ngày” theo cách hiểu mơ hồ.

Thời điểm này được tính theo calendar plan và business timezone
`Asia/Ho_Chi_Minh`:

```text
MONTHLY:  startAt + 1 calendar month
YEARLY:   startAt + 1 calendar year
```

### `effectively active` — ACTIVE có hiệu lực thực tế

Đây là business concept, không phải một enum mới:

```text
status == ACTIVE
AND startAt <= now
AND now < expiresAt
```

Phân biệt hai khái niệm:

| Khái niệm | Ý nghĩa |
| --- | --- |
| Persisted `status == ACTIVE` | Cột database đang lưu là `ACTIVE`. |
| Effectively active | `status == ACTIVE` **và** `now` nằm trong effective window. |

Một License có thể vẫn lưu `ACTIVE` trong vài giây trước khi expiry scheduler
chạy, nhưng vẫn **không** effectively active khi `now >= expiresAt`.

### `PENDING` — đã tạo nhưng chưa có hiệu lực

License đã tồn tại và hợp lệ về mặt dữ liệu, nhưng chưa được phép phục vụ
business mới. Hai trường hợp thường gặp:

- License mới có `startAt` trong tương lai.
- Early renew tạo kỳ mới nối tiếp kỳ cũ.

`PENDING` có thể chuyển sang `ACTIVE` khi tới `startAt`, hoặc `CANCELLED` khi
Admin chủ động hủy kỳ chưa bắt đầu. Phase hiện tại **không tự động dùng**
`PENDING -> EXPIRED`: scheduler chạy trễ nhưng kỳ vẫn còn effective phải recovery
và activate; nếu toàn bộ effective window đã trôi qua thì ghi nhận reconciliation
anomaly để xử lý thủ công cho đến khi có business policy riêng.

### `ACTIVE` — trạng thái được phép sử dụng

`ACTIVE` là state biểu thị License đã được kích hoạt. Tuy nhiên khi viết rule
business, luôn dùng `effectively active` thay vì chỉ kiểm tra `status == ACTIVE`.

### `SUSPENDED` — tạm ngưng thủ công

Admin tạm thời chặn quyền nhận business mới vì lý do compliance, pháp lý, an
toàn hoặc hợp đồng. Đồng hồ `startAt`/`expiresAt` vẫn tiếp tục chạy. Vì nguyên
nhân có thể còn hiệu lực qua nhiều kỳ, command suspend đồng thời đặt Station vào
`COMPLIANCE_HOLD`; không dùng status của một period cũ làm cross-period hold:

```text
SUSPENDED -> ACTIVE       chỉ khi còn trong effective window
SUSPENDED -> CANCELLED    nếu chấm dứt vĩnh viễn
SUSPENDED -> EXPIRED      nếu thời hạn đi qua
```

SUSPENDED không làm Station, ChargePoint hoặc Connector tự động đổi status,
nhưng eligibility policy phải loại Station khỏi discovery/booking khi Licensing
Control không ở `CLEAR`.

### `CANCELLED` — hủy thủ công, terminal

Admin chấm dứt **một License period** trước khi hết hạn. Đây là terminal state
của row đó: không reactivate và không sửa ngược về ACTIVE. `CANCELLED` tự nó
không có nghĩa Station bị revoke vĩnh viễn; xem
[mục 7.6](#76-cancel-period-và-revoke-relationship).

### `EXPIRED` — hết hạn theo thời gian, terminal

License đã đi qua `expiresAt`. Scheduler/reconciliation chuyển state sang
`EXPIRED` và ghi event `EXPIRED` với actor `SYSTEM`. Đây cũng là terminal state.

### `terminal state` — trạng thái kết thúc

Là state không cho phép quay lại state đang hoạt động. Trong License hiện tại:

```text
CANCELLED, EXPIRED
```

Không nhầm terminal với “không effectively active”: `PENDING` và `SUSPENDED`
không effectively active nhưng chưa terminal.

### `compliance hold` — chặn quyền vận hành xuyên kỳ

Là trạng thái kiểm soát ở cấp Station, không phải `LicenseStatus`. Hold được đặt
khi quyền vận hành phải tạm dừng vì an toàn, pháp lý hoặc tuân thủ và vẫn tồn tại
khi một License period chuyển từ `SUSPENDED` sang `EXPIRED`. Trong lúc hold còn
hiệu lực, hệ thống không được issue, renew, reactivate hoặc auto-activate
successor.

### `revoke licensing relationship` — chấm dứt quan hệ cấp phép

Là command riêng ở cấp Station để ngăn mọi vận hành hiện tại và tương lai. Nó
khác `cancelLicense(id)`, vốn chỉ terminate một period. Revoke đặt Licensing
Control thành `REVOKED`, hủy các period non-terminal liên quan và chặn issue,
renew, reactivate, scheduler activation cho đến khi có use case thiết lập quan
hệ mới được phê duyệt rõ ràng.

### `renew` — tạo kỳ subscription mới

`renew` không phải transition của License cũ. Nó tạo một row License mới theo
mô hình append-only:

```text
License cũ giữ nguyên
        + License mới bắt đầu tại max(now, old.expiresAt)
```

Vì vậy lịch sử không bị ghi đè và audit có thể trả lời chính xác từng kỳ đã
được cấp.

### `reactivate` — kích hoạt lại cùng một kỳ

`reactivate` chỉ áp dụng cho License `SUSPENDED` còn trong effective window. Nó
gọi `license.activate(now)` trên **chính row cũ**, khác hoàn toàn với `renew`.

### `append-only subscription period`

Mỗi kỳ subscription là một record riêng; không kéo dài hoặc đổi ngược record
cũ. History được xây bằng cách đọc các row theo `startAt DESC`.

### `status event` và `reason`

- `status event`: audit fact, ví dụ `SUSPENDED`, `REACTIVATED`, `EXPIRED`.
- `reason`: căn cứ nghiệp vụ do Admin nhập cho command thủ công.

`ISSUED`, `ACTIVATED` và `EXPIRED` tự động có thể có `reason = null`; không tạo
reason giả chỉ để lấp field.

---

## 1. Bức tranh Tổng quan: Frontend Cần Những Gì & Tại Sao?

Frontend phân hệ **Admin License** được tổ chức thành **3 khối giao diện chính**:

```
+---------------------------------------------------------------------------------------------+
| 1. BẢNG TỔNG QUAN VẬN HÀNH (Operational Summary Table - Trang chính)                        |
| - Scan nhanh: Mã License | Trạm sạc | Chủ trạm | Gói | Hết hạn (còn N ngày) | Trạng thái    |
| - Thao tác: Nút Gia hạn nhanh, Menu Dropdown (Chi tiết, Tạm ngưng, Kích hoạt lại, Hủy)       |
| - Filter & Search: Ô tìm kiếm đa trường (Mã, Trạm, Chủ), Lọc theo Tab trạng thái, Phân trang  |
+---------------------------------------------------------------------------------------------+
                                       │ (Click dòng / Menu)
                                       ▼
+---------------------------------------------------------------------------------------------+
| 2. DRAWER CHI TIẾT LICENSE (License Detail Drawer - Slide-over 540px)                       |
| - Snapshot nghiệp vụ: Mức phí đã thu, Thời hạn hiệu lực, Ngày/người tạo ban đầu, Concurrency|
| - Lifecycle Actions Contextual: Nút bấm thay đổi linh hoạt theo trạng thái domain           |
+---------------------------------------------------------------------------------------------+
                                       │
                   ┌───────────────────┴───────────────────┐
                   ▼                                       ▼
+------------------------------------+   +------------------------------------+
| 3A. TAB 1: NHẬT KÝ TRẠNG THÁI     |   | 3B. TAB 2: CÁC KỲ CỦA TRẠM         |
| (Status Events Audit Timeline)     |   | (Station License Subscription Hist)|
| - Timeline sự kiện của CHÍNH       |   | - Danh sách TẤT CẢ các kỳ License  |
|   License này (ISSUED, ACTIVATED,  |   |   trong lịch sử của trạm đó (kỳ cũ |
|   SUSPENDED, REACTIVATED, CANCELLED|   |   đã hết hạn, kỳ hiện tại, kỳ mới  |
| - Sắp xếp: performedAt DESC (mới   |   |   đang PENDING chờ hiệu lực)       |
|   nhất lên đầu), Actor & Lý do     |   | - Sắp xếp: startAt DESC            |
+------------------------------------+   +------------------------------------+
```

### 1.1. Thứ tự Nghiệp vụ: Cấp License & Phê duyệt Trạm (Station Approval Flow)

Theo quy định chính sách của hệ thống (BR-STA-01 & `StationApprovalPolicy`):
- Trạm sạc **chỉ được phép phê duyệt (Approve)** khi và chỉ khi trạm đó đã có ít nhất một License **effectively active** (đang có hiệu lực thực tế tại thời điểm xét duyệt).
- Do đó, quy trình vận hành chuẩn xác là:
  ```text
  1. Trạm đăng ký mới (Chờ duyệt hồ sơ)
         ↓
  2. Admin Cấp License (Issue License) nhằm thiết lập quyền vận hành/kinh doanh trên ChargeOps
         ↓
  3. License thỏa mãn điều kiện effectively active (status == ACTIVE && startAt <= now < expiresAt)
         ↓
  4. Admin Phê duyệt Trạm (Approve Station) — Policy kiểm tra hợp lệ và chuyển trạm sang ACTIVE
  ```

---

## 2. Thiết kế Tìm kiếm Động bằng JPA Specification (`LicenseSpecification`)

Thay vì viết query JPQL thủ công phức tạp và dễ lỗi cú pháp, Backend sử dụng **`JpaSpecificationExecutor<License>`** kết hợp `LicenseFilter` để tạo query động, an toàn kiểu dữ liệu (type-safe) và tối ưu hiệu năng.

### 2.1. Filter DTO (`LicenseFilter.java`)

`LicenseFilter` được thiết kế theo mô hình **Dynamic Dual-Layer Filter**:
- **Tầng 1 (Primary - Frontend UI Bindings)**: `search` (Global text search) và `status` (Tab lọc trạng thái). Đây là 2 tham số chính mà giao diện `Licenses.tsx` gửi lên thường xuyên qua ô search tổng quát và các tab trạng thái.
- **Tầng 2 (Deep-linking & Specific Filters)**: Các trường lọc chi tiết (`stationId`, `licenseCode`, `stationCode`, `stationName`, `ownerName`, `ownerEmail`). Phục vụ việc điều hướng chính xác giữa các module quản trị (ví dụ: từ Quản lý trạm hoặc Quản lý tài khoản nhảy sang danh sách License đã lọc sẵn theo trạm/owner cụ thể) mà không bắt buộc Frontend phải dựng thêm form Advanced Filter rườm rà.

```java
package com.thang.chargeops.station.dto.license.filter;

import com.thang.chargeops.common.enums.LicenseStatus;

import java.util.UUID;

public record LicenseFilter(
        String search,
        String licenseCode,
        LicenseStatus status,
        String ownerName,
        String ownerEmail,
        String stationName,
        String stationCode,
        UUID stationId
) {
}
```

### 2.2. Specification Động (`LicenseSpecification.java`)

Implementation hiện tại compose theo nhóm nghiệp vụ:

```java
return Specification.allOf(
        matchesSearch(filter.search()),
        matchesLicense(filter),
        matchesStation(filter),
        matchesOwner(filter)
);
```

Quy ước:

- Filter không đóng góp predicate trả `Specification.unrestricted()` (khi FE chỉ gửi `search` và `status`, các trường mang giá trị `null` sẽ tự động được bỏ qua, hoàn toàn không sinh thêm mệnh đề `WHERE` hay `JOIN` thừa trong SQL).
- `licenseCode` và `stationCode` dùng prefix match (`value%`).
- `stationName` và `ownerName` dùng contains match (`%value%`).
- `ownerEmail` filter riêng dùng exact match, không phân biệt hoa thường.
- Global search (`search`) dùng prefix cho các code (`licenseCode`, `stationCode`) và contains cho name/email (`stationName`, `ownerName`, `ownerEmail`).
- `%`, `_` và escape character từ input phải được escape.
- Station/owner join được tái sử dụng khi nhiều specification cùng cần.
- Source code và integration tests là nguồn sự thật; không duy trì bản sao đầy đủ của class trong tài liệu này để tránh drift.

### 2.3. Repository Interface (`LicenseRepository.java`)

```java
package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.License;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LicenseRepository extends JpaRepository<License, UUID>, JpaSpecificationExecutor<License> {

    @Override
    @EntityGraph(attributePaths = {"station", "owner"})
    Page<License> findAll(Specification<License> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"station", "owner"})
    Optional<License> findWithDetailsById(UUID id);

    @Query("""
            SELECT l FROM License l
            JOIN FETCH l.station s
            JOIN FETCH l.owner owner
            WHERE s.id = :stationId
            ORDER BY l.startAt DESC
            """)
    List<License> findStationLicenseHistory(@Param("stationId") UUID stationId);

    boolean existsByLicenseCode(String licenseCode);
}
```

---

## 3. Kiến trúc Phân tách Trách nhiệm Controllers (Query & Lifecycle Separation)

Để code rõ ràng, tuân thủ nguyên lý Single Responsibility và tách biệt giữa **Xem dữ liệu (Read/Query)** và **Thao tác nghiệp vụ (Command/Lifecycle)**, hệ thống phân thành 2 Controller riêng:

1. **`AdminLicenseQueryController` (Chuyên READ/Tra cứu)**:
   - Base Path: `/api/v1/admin/licenses`
   - Nhiệm vụ: Tìm kiếm phân trang, Xem chi tiết, Lấy lịch sử chuyển trạng thái (Audit Events), Lấy lịch sử kỳ hạn trạm.
2. **`AdminLicenseController` (Chuyên COMMAND/Lifecycle Actions)**:
   - Base Path: `/api/v1/stations/{stationId}/licenses` và `/api/v1/admin/licenses/{licenseId}/...`
   - Nhiệm vụ: Cấp mới License (`issue`), Tạm ngưng (`suspend`), Kích hoạt lại (`reactivate`), Hủy bỏ (`cancel`), Gia hạn (`renew`).

---

## 4. Chi tiết API Contracts (Frontend $\leftrightarrow$ Backend)

### 4.1. [QUERY] API 1: Tìm kiếm & Phân trang License (Platform Admin)

- **HTTP Method**: `GET`
- **Path**: `/api/v1/admin/licenses`
- **Quyền**: `hasRole('ADMIN')`
- **Query Parameters**:

#### A. Tham số Tìm kiếm Chính (Frontend UI Bindings)

| Tham số | Kiểu dữ liệu | Bắt buộc | Mặc định | Ý nghĩa đối với Frontend (`Licenses.tsx`) |
| :--- | :--- | :--- | :--- | :--- |
| `search` | `String` | Không | `null` | **Global Search**: Tìm kiếm đa trường (Mã License `LIC-...`, mã trạm `ST-...`, tên trạm, tên chủ trạm, email) từ ô tìm kiếm chính. |
| `status` | `LicenseStatus` | Không | `null` | **Status Filter Tab**: Lọc theo tab trạng thái (`ACTIVE`, `PENDING`, `SUSPENDED`, `EXPIRED`, `CANCELLED`). |

#### B. Tham số Lọc Chuyên biệt & Deep-link (Specific / Programmatic Filters)

| Tham số | Kiểu dữ liệu | Bắt buộc | Mặc định | Kịch bản sử dụng (Use Cases) |
| :--- | :--- | :--- | :--- | :--- |
| `stationId` | `UUID` | Không | `null` | Lọc license của một trạm cụ thể theo ID (Dùng cho Drawer hoặc liên kết từ quản lý trạm). |
| `stationCode` | `String` | Không | `null` | Lọc theo mã trạm chính xác dạng prefix (`ST-1001%`). |
| `stationName` | `String` | Không | `null` | Lọc theo tên trạm (`%FastCharge%`). |
| `licenseCode` | `String` | Không | `null` | Lọc chính xác mã giấy phép dạng prefix (`LIC-001%`). |
| `ownerName` | `String` | Không | `null` | Lọc theo tên chủ sở hữu trạm (`%EVGo%`). |
| `ownerEmail` | `String` | Không | `null` | Lọc chính xác theo email chủ sở hữu trạm (case-insensitive). |

> 💡 **Lưu ý triển khai**: Frontend hiện tại chỉ cần truyền các tham số ở **Nhóm A**. Nhóm B đóng vai trò mở rộng cho deep-linking và các công cụ tích hợp mà không làm tăng độ phức tạp của giao diện người dùng.

#### C. Tham số Phân trang & Sắp xếp (Pagination & Sorting)

| Tham số | Kiểu dữ liệu | Bắt buộc | Mặc định | Ý nghĩa |
| :--- | :--- | :--- | :--- | :--- |
| `pageNo` | `int` | Không | `0` | Chỉ số trang (Frontend ChargeOps dùng **0-indexed**: 0 = Trang 1). |
| `pageSize` | `int` | Không | `8` | Số bản ghi trên mỗi trang (mặc định bảng Admin hiển thị 8 dòng). |
| `sort` | `String` | Không | `createdAt,desc` | Tiêu chí sắp xếp. |

- **Response Body (`ApiResult<List<AdminLicenseListItemResponse>>`)**:

```json
{
  "data": [
    {
      "id": "71c9fc68-3bb8-49e1-a27e-c301ad3519b0",
      "licenseCode": "LIC-001001",
      "stationId": "b1a2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
      "stationCode": "ST-1001",
      "stationName": "Trạm sạc Hà Đông FastCharge",
      "ownerName": "Công ty Cổ phần EVGo",
      "plan": "YEARLY",
      "startAt": "2025-09-12T00:00:00Z",
      "expiresAt": "2026-09-12T00:00:00Z",
      "status": "ACTIVE",
      "daysLeft": 76,
      "expiringSoon": false,
      "feeAmount": 5000000.00
    }
  ],
  "meta": {
    "page": 1,
    "size": 8,
    "totalElements": 16,
    "totalPages": 2,
    "serverTime": 1724068800000,
    "apiVersion": "1.0.0",
    "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
  }
}
```

> **Quy ước `daysLeft` và `expiringSoon`**:
> - `daysLeft`: Trả về số ngày từ thời điểm hiện tại đến `expiresAt` (theo múi giờ `Asia/Ho_Chi_Minh`). Nếu License đã quá hạn, trả về giá trị âm (ví dụ: `-5` tức là đã quá hạn 5 ngày) để Frontend có thể hiển thị cảnh báo chính xác.
> - `expiringSoon`: Trả về `true` khi License đang **effectively active** (`status == ACTIVE && startAt <= now && now < expiresAt`) và `0 <= daysLeft <= 15`.

---

### 4.2. [QUERY] API 2: Chi tiết License (Detail Drawer)

- **HTTP Method**: `GET`
- **Path**: `/api/v1/admin/licenses/{licenseId}`
- **Quyền**: `hasRole('ADMIN')`
- **Response Body (`ApiResult<AdminLicenseDetailResponse>`)**:

```json
{
  "data": {
    "id": "71c9fc68-3bb8-49e1-a27e-c301ad3519b0",
    "licenseCode": "LIC-001001",
    "stationId": "b1a2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
    "stationCode": "ST-1001",
    "stationName": "Trạm sạc Hà Đông FastCharge",
    "ownerId": "f0e1d2c3-b4a5-9687-8594-a3b2c1d0e1f2",
    "ownerName": "Công ty Cổ phần EVGo",
    "ownerEmail": "contact@evgo.vn",
    "plan": "YEARLY",
    "feeAmount": 5000000.00,
    "startAt": "2025-09-12T00:00:00Z",
    "expiresAt": "2026-09-12T00:00:00Z",
    "status": "ACTIVE",
    "daysLeft": 76,
    "expiringSoon": false,
    "createdAt": "2025-09-10T08:30:00Z",
    "recordedByName": "Admin Hệ Thống (Trần Quản Trị)"
  },
  "meta": {
    "serverTime": 1724068800000,
    "apiVersion": "1.0.0",
    "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
  }
}
```

---

### 4.3. [QUERY] API 3: Lấy Nhật ký Chuyển trạng thái (Status Events Timeline)

- **HTTP Method**: `GET`
- **Path**: `/api/v1/admin/licenses/{licenseId}/status-events`
- **Quyền**: `hasRole('ADMIN')`
- **Mục đích**: Phục vụ **Tab 1 ("Nhật ký trạng thái")** trong Drawer để xem audit timeline của *chính License này*.
- **Quy tắc Sắp xếp & Invariant**:
  - **Ordering**: Sắp xếp cố định theo `performedAt DESC` (sự kiện mới nhất xuất hiện trên cùng).
  - **Actor Invariant**: Khi `actorType = USER`, `performedBy` bắt buộc phải là User Profile hợp lệ của Admin thực hiện thao tác. Khi `actorType = SYSTEM`, `performedByName` trả về `"SYSTEM"`.
- **Response Body (`ApiResult<List<LicenseStatusEventResponse>>`)**:

```json
{
  "data": [
    {
      "id": "a9b8c7d6-e5f4-3a2b-1c0d-ef1234567890",
      "licenseId": "71c9fc68-3bb8-49e1-a27e-c301ad3519b0",
      "eventType": "SUSPENDED",
      "fromStatus": "ACTIVE",
      "toStatus": "SUSPENDED",
      "reason": "Tạm ngưng do trạm vi phạm quy chuẩn an toàn phòng cháy chữa cháy",
      "actorType": "USER",
      "performedByName": "Admin Hệ Thống (Lê Kiểm Soát)",
      "performedAt": "2026-04-10T14:20:00Z"
    },
    {
      "id": "b8a7c6d5-e4f3-2a1b-0c9d-ef0987654321",
      "licenseId": "71c9fc68-3bb8-49e1-a27e-c301ad3519b0",
      "eventType": "ACTIVATED",
      "fromStatus": "PENDING",
      "toStatus": "ACTIVE",
      "reason": null,
      "actorType": "SYSTEM",
      "performedByName": "SYSTEM",
      "performedAt": "2025-09-12T00:00:00Z"
    },
    {
      "id": "c7a6b5d4-e3f2-1a0b-9c8d-ef1122334455",
      "licenseId": "71c9fc68-3bb8-49e1-a27e-c301ad3519b0",
      "eventType": "ISSUED",
      "fromStatus": null,
      "toStatus": "PENDING",
      "reason": null,
      "actorType": "USER",
      "performedByName": "Admin Hệ Thống (Trần Quản Trị)",
      "performedAt": "2025-09-10T08:30:00Z"
    }
  ],
  "meta": {
    "serverTime": 1724068800000,
    "apiVersion": "1.0.0",
    "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
  }
}
```

---

### 4.4. [QUERY] API 4: Lịch sử Các kỳ License của Trạm (Station Subscription History)

- **HTTP Method**: `GET`
- **Path**: `/api/v1/stations/{stationId}/licenses`
- **Quyền**: `hasRole('ADMIN')` hoặc `hasRole('STATION_OWNER')`
- **Mục đích**: Phục vụ **Tab 2 ("Các kỳ License của trạm")** trong Drawer để xem danh sách tất cả các kỳ hạn subscription trong lịch sử của trạm đó.
- **Quy tắc Nghiệp vụ (Append-Only Periods)**:
  - Danh sách sắp xếp theo `startAt DESC`.
  - Mỗi kỳ là một bản ghi License độc lập (ví dụ: Kỳ cũ `EXPIRED`, Kỳ hiện tại `ACTIVE`, Kỳ mới gia hạn `PENDING` chờ hiệu lực).
- **Response Body (`ApiResult<List<AdminLicenseListItemResponse>>`)**: Danh sách các kỳ License của trạm.

---

### 4.5. [COMMAND] Command Request DTOs

Các thao tác can thiệp thủ công từ Admin (`suspend`, `reactivate`, `cancel`) **bắt buộc phải có lý do nghiệp vụ rõ ràng** (`@NotBlank`):

```java
package com.thang.chargeops.station.dto.license.request;

import com.thang.chargeops.exception.errormessage.LicenseErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LicenseStatusChangeRequest(
        @NotBlank(message = LicenseErrorMessage.STATUS_CHANGE_REASON_REQUIRED_KEY)
        @Size(min = 5, max = 500, message = LicenseErrorMessage.STATUS_CHANGE_REASON_SIZE_KEY)
        String reason
) {
}
```

---

### 4.6. Chính sách Audit Reason & Danh mục Preset Suggestions cho Frontend

#### A. Phân định ranh giới Domain (License vs Station/Charger Operational Status)
- **Station / Charger Status (`MAINTENANCE`, `OFFLINE`)**: Dành cho sự cố kỹ thuật thông thường (bảo trì 1 trụ sạc, thay đầu cáp, mất điện tạm thời vài giờ...).
- **License Status (`SUSPENDED`, `CANCELLED`)**: Đại diện cho **Tư cách pháp lý & Quyền được phép kinh doanh/vận hành trên nền tảng (Operating Authority & Compliance)**. Do đó, lý do can thiệp License phải phản ánh các vấn đề ở cấp độ tư cách pháp lý/an toàn/hợp đồng của trạm.

#### B. Phân biệt `Event Description` vs `Reason`
- **Event Description**: Mô tả bản chất sự kiện (render trên UI qua i18n theo `eventType`, ví dụ: *"License được cấp"*, *"License đã kích hoạt"*, *"License đã hết hạn"*).
- **Reason**: Căn cứ thực tế đằng sau một quyết định can thiệp thủ công từ Admin. Đối với sự kiện tự nhiên (`ISSUED`, `ACTIVATED`, `EXPIRED`), `reason` có giá trị `null` hoàn toàn hợp lệ (không tạo fallback giả dạng tautology).

#### C. Bảng Preset Reason Chips (Frontend UX Helper)

| Action | Event Type | Bắt buộc `reason`? | Gợi ý Preset Chips (Frontend i18n / Quick Chips) |
| :--- | :--- | :--- | :--- |
| **Cấp License** | `ISSUED` | **Không** (`null`) | *(Không cần)* |
| **Kích hoạt** | `ACTIVATED` | **Không** (`null`) | *(Không cần)* |
| **Tạm ngưng** | `SUSPENDED` | **BẮT BUỘC** | 🏷️ *Vi phạm quy chuẩn an toàn vận hành trạm sạc*<br>🏷️ *Chờ bổ sung/xác minh hồ sơ pháp lý và kiểm định an toàn*<br>🏷️ *Yêu cầu tạm dừng từ cơ quan chức năng/chính quyền địa phương*<br>🏷️ *Chủ trạm chủ động yêu cầu tạm ngưng quyền kinh doanh*<br>🏷️ *Tranh chấp quyền khai thác mặt bằng hoặc vi phạm thỏa thuận dịch vụ* |
| **Kích hoạt lại**| `REACTIVATED`| **BẮT BUỘC** | 🏷️ *Trạm đã khắc phục xong vi phạm và vượt qua kiểm định an toàn*<br>🏷️ *Đã bổ sung đầy đủ hồ sơ pháp lý và giấy phép liên quan*<br>🏷️ *Cơ quan chức năng đã cho phép trạm hoạt động trở lại*<br>🏷️ *Chủ trạm hoàn tất xử lý và đề nghị khôi phục quyền kinh doanh* |
| **Hủy bỏ** | `CANCELLED` | **BẮT BUỘC** | 🏷️ *Chủ trạm chấm dứt hợp đồng hợp tác vận hành ChargeOps*<br>🏷️ *Trạm dừng hoạt động vĩnh viễn do giải tỏa/thu hồi mặt bằng*<br>🏷️ *Vi phạm nghiêm trọng chính sách không thể khắc phục* |
| **Hết hạn** | `EXPIRED` | **Không** (`null`) | *(Hệ thống tự động xử lý)* |

---

## 5. Cấu hình MapStruct Mapper (`LicenseMapper.java`)

Sử dụng **MapStruct** (`@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)`) đồng bộ với `StationMapper.java` hiện có trong dự án:

```java
package com.thang.chargeops.station.mapper;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.license.response.AdminLicenseDetailResponse;
import com.thang.chargeops.station.dto.license.response.AdminLicenseListItemResponse;
import com.thang.chargeops.station.dto.license.response.LicenseStatusEventResponse;
import com.thang.chargeops.station.dto.license.response.IssueLicenseResponse;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LicenseMapper {

    // 1. Issue License Response (sẵn có)
    @Mapping(target = "stationId", source = "station.id")
    IssueLicenseResponse toIssueLicenseResponse(License license);

    // 2. Map sang ListItemResponse (dùng cho Table List & Station History)
    @Mapping(target = "stationId", source = "license.station.id")
    @Mapping(target = "stationCode", source = "license.station.stationCode")
    @Mapping(target = "stationName", source = "license.station.name")
    @Mapping(target = "ownerName", expression = "java(resolveOwnerName(license.getOwner()))")
    @Mapping(target = "daysLeft", expression = "java(license.calculateDaysLeft())")
    @Mapping(target = "expiringSoon", expression = "java(license.isExpiringSoon())")
    AdminLicenseListItemResponse toListItemResponse(License license);

    List<AdminLicenseListItemResponse> toListItemResponseList(List<License> licenses);

    // 3. Map sang DetailResponse (dùng cho Detail Drawer)
    @Mapping(target = "stationId", source = "license.station.id")
    @Mapping(target = "stationCode", source = "license.station.stationCode")
    @Mapping(target = "stationName", source = "license.station.name")
    @Mapping(target = "ownerId", source = "license.owner.id")
    @Mapping(target = "ownerName", expression = "java(resolveOwnerName(license.getOwner()))")
    @Mapping(target = "ownerEmail", source = "license.owner.email")
    @Mapping(target = "daysLeft", expression = "java(license.calculateDaysLeft())")
    @Mapping(target = "expiringSoon", expression = "java(license.isExpiringSoon())")
    @Mapping(target = "recordedByName", source = "recordedByName")
    AdminLicenseDetailResponse toDetailResponse(License license, String recordedByName);

    // 4. Map Status Event (dùng cho Status Events Timeline Tab 1)
    @Mapping(target = "licenseId", source = "event.license.id")
    @Mapping(target = "performedByName", expression = "java(resolvePerformerName(event.getPerformedBy(), event.getActorType()))")
    LicenseStatusEventResponse toStatusEventResponse(LicenseStatusEvent event);

    List<LicenseStatusEventResponse> toStatusEventResponseList(List<LicenseStatusEvent> events);

    // --- Presentation Formatting Helpers ---

    default String resolveOwnerName(UserProfile owner) {
        if (owner == null) return null;
        return (owner.getDisplayName() != null && !owner.getDisplayName().isBlank())
                ? owner.getDisplayName()
                : owner.getEmail();
    }

    default String resolvePerformerName(UserProfile performedBy, com.thang.chargeops.common.enums.LicenseStatusActorType actorType) {
        if (actorType == com.thang.chargeops.common.enums.LicenseStatusActorType.SYSTEM) {
            return "SYSTEM";
        }
        if (performedBy == null) {
            return "Quản trị viên (Không xác định)";
        }
        return (performedBy.getDisplayName() != null && !performedBy.getDisplayName().isBlank())
                ? performedBy.getDisplayName()
                : performedBy.getEmail();
    }
}
```

---

## 6. Code Controller Mẫu

### 6.1. `AdminLicenseQueryController.java` (Read & Search Operations)

```java
package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.license.response.AdminLicenseDetailResponse;
import com.thang.chargeops.station.dto.license.response.AdminLicenseListItemResponse;
import com.thang.chargeops.station.dto.license.response.LicenseStatusEventResponse;
import com.thang.chargeops.station.dto.license.filter.LicenseFilter;
import com.thang.chargeops.station.service.LicenseQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/licenses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminLicenseQueryController {

    private final LicenseQueryService licenseQueryService;
    private final LicenseStatusEventService licenseStatusEventService;

    /**
     * 1. Tìm kiếm, lọc Specs và phân trang danh sách License
     */
    @GetMapping
    public ResponseEntity<ApiResult<List<AdminLicenseListItemResponse>>> searchLicenses(
            LicenseFilter filter,
            @PageableDefault(size = 8, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<AdminLicenseListItemResponse> page = licenseQueryService.searchLicenses(filter, pageable);
        return ResponseEntity.ok(ApiResult.successPage(page));
    }

    /**
     * 2. Xem thông tin chi tiết 1 License (Drawer)
     */
    @GetMapping("/{licenseId}")
    public ResponseEntity<ApiResult<AdminLicenseDetailResponse>> getLicenseDetail(
            @PathVariable UUID licenseId
    ) {
        var detail = licenseQueryService.getLicenseDetail(licenseId);
        return ResponseEntity.ok(ApiResult.success(detail));
    }

    /**
     * 3. Lấy danh sách sự kiện chuyển trạng thái (Drawer Tab 1 - Status Events Timeline)
     * Backend đảm bảo ordering theo performedAt DESC.
     */
    @GetMapping("/{licenseId}/status-events")
    public ResponseEntity<ApiResult<List<LicenseStatusEventResponse>>> getLicenseStatusEvents(
            @PathVariable UUID licenseId
    ) {
        var events = licenseStatusEventService.getLicenseStatusEvents(licenseId);
        return ResponseEntity.ok(ApiResult.success(events));
    }

}
```

---

### 6.2. `StationLicenseQueryController.java` (hoặc đặt trong `StationController.java`)

```java
package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.license.response.AdminLicenseListItemResponse;
import com.thang.chargeops.station.service.LicenseQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "stations")
@RequiredArgsConstructor
public class StationLicenseQueryController {

    private final LicenseQueryService licenseQueryService;

    /**
     * 4. Lấy lịch sử tất cả các kỳ License của 1 trạm sạc (Drawer Tab 2 - Station Periods)
     * Backend đảm bảo ordering theo startAt DESC.
     */
    @GetMapping("/{stationId}/licenses")
    @PreAuthorize("hasRole('ADMIN') or hasRole('STATION_OWNER')")
    public ResponseEntity<ApiResult<List<AdminLicenseListItemResponse>>> getStationLicenseHistory(
            @PathVariable UUID stationId
    ) {
        var history = licenseQueryService.getStationLicenseHistory(stationId);
        return ResponseEntity.ok(ApiResult.success(history));
    }
}
```

---

### 6.3. `AdminLicenseController.java` (Lifecycle & Station-scoped Commands)

```java
package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.license.request.LicenseStatusChangeRequest;
import com.thang.chargeops.station.dto.license.request.IssueLicenseRequest;
import com.thang.chargeops.station.dto.license.response.IssueLicenseResponse;
import com.thang.chargeops.station.service.LicenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN)
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminLicenseController {

    private final LicenseService licenseService;

    /**
     * 1. Cấp License mới cho Station để thiết lập quyền kinh doanh (Điều kiện tiên quyết để duyệt Station)
     */
    @PostMapping("stations/{stationId}/licenses")
    public ResponseEntity<ApiResult<IssueLicenseResponse>> issueLicense(
            @PathVariable UUID stationId,
            @Valid @RequestBody IssueLicenseRequest request
    ) {
        var response = licenseService.issueLicense(stationId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResult.success(response));
    }

    /**
     * 2. Tạm ngưng quyền vận hành của License (Bắt buộc nhập lý do)
     */
    @PostMapping("admin/licenses/{licenseId}/suspend")
    public ResponseEntity<ApiResult<Void>> suspendLicense(
            @PathVariable UUID licenseId,
            @Valid @RequestBody LicenseStatusChangeRequest request
    ) {
        licenseService.suspendLicense(licenseId, request.reason());
        return ResponseEntity.ok(ApiResult.success());
    }

    /**
     * 3. Khôi phục / Kích hoạt lại License đang bị tạm ngưng (Bắt buộc nhập lý do)
     */
    @PostMapping("admin/licenses/{licenseId}/reactivate")
    public ResponseEntity<ApiResult<Void>> reactivateLicense(
            @PathVariable UUID licenseId,
            @Valid @RequestBody LicenseStatusChangeRequest request
    ) {
        licenseService.reactivateLicense(licenseId, request.reason());
        return ResponseEntity.ok(ApiResult.success());
    }

    /**
     * 4. Chấm dứt riêng License period được chọn (Bắt buộc nhập lý do).
     * Không dùng API này để revoke toàn bộ quan hệ cấp phép của Station.
     */
    @PostMapping("admin/licenses/{licenseId}/cancel")
    public ResponseEntity<ApiResult<Void>> cancelLicense(
            @PathVariable UUID licenseId,
            @Valid @RequestBody LicenseStatusChangeRequest request
    ) {
        licenseService.cancelLicense(licenseId, request.reason());
        return ResponseEntity.ok(ApiResult.success());
    }

    /**
     * 5. Ghi nhận gia hạn kỳ License mới ngoài nền tảng (Append-only: Tạo 1 License row mới)
     */
    @PostMapping("admin/licenses/{licenseId}/renew")
    public ResponseEntity<ApiResult<IssueLicenseResponse>> renewLicense(
            @PathVariable UUID licenseId,
            @Valid @RequestBody(required = false) IssueLicenseRequest request
    ) {
        var response = licenseService.renewLicense(licenseId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResult.success(response));
    }
}
```

---

## 7. Admin License Lifecycle Policy

Đây là policy chuẩn cho các command API của Admin. Phần này là nguồn sự thật
cho state machine; controller mẫu và checklist phía dưới phải tuân theo policy
này.

### 7.1. Có cần tạo `LicenseLifecyclePolicy` giống `StationApprovalPolicy` không?

Không nên chọn một trong hai cực đoan là “mọi rule nằm trong Entity” hoặc “mọi
rule nằm trong Policy class”. Hai lớp giải quyết hai loại invariant khác nhau:

| Lớp | Trách nhiệm | Ví dụ |
| --- | --- | --- |
| Controller / Bean Validation | Authentication/authority và hình dạng request | ADMIN-only, `reason` từ 5–500 ký tự, `plan` bắt buộc |
| `License` entity | Invariant chỉ phụ thuộc state và dữ liệu của chính License | `ACTIVE -> SUSPENDED`, terminal không cancel, `activate(now)` phải nằm trong effective window |
| Application service | Transaction orchestration | load aggregate, gọi entity method, ghi event, flush/map response |
| Lifecycle policy hoặc domain service | Rule cần repository, License khác, Station khác hoặc một quyết định dùng lại ở nhiều command | không có License ACTIVE khác khi reactivate; target renew phải là kỳ mới nhất; không được tạo hai kỳ renewal kế tiếp |
| Database | Final concurrency/integrity guard | `@Version`, unique license code, partial unique active License/station |

Với code hiện tại, `suspend`, `activate`, `cancel`, `markExpired` trong Entity
vẫn phải được sử dụng. Không tạo policy chỉ để viết lại cùng một `if status` rồi
set status lần thứ hai.

Đánh giá trực tiếp các method hiện tại:

| Method | Quyết định |
| --- | --- |
| `license.activate(now)` | Giữ; method đã bảo vệ source state và effective window. Service/policy bổ sung active-license conflict giữa nhiều row. |
| `license.cancel()` | Giữ; method đã chặn terminal state. Request/service chịu trách nhiệm reason và audit. |
| `license.markExpired(now)` | Giữ; chỉ scheduler/reconciliation gọi và ghi event SYSTEM. |
| `license.suspend()` | Nên đổi thành `suspend(now)` hoặc thêm invariant tương đương trong Entity, vì một row còn status `ACTIVE` nhưng đã qua `expiresAt` không còn đủ điều kiện suspend thủ công. |
| `renew` | Không thêm `oldLicense.renew()`. Đây là use case tạo aggregate mới; dùng renewal policy + `License.issue(...)`. |

Các method Entity hiện ném `IllegalStateException`; service không được để lỗi đó
rơi thành HTTP `500`. Hướng sạch là dùng một domain exception cho invalid
transition rồi map tập trung sang `409 INVALID_STATUS_TRANSITION` hoặc
`409 LICENSE_OUTSIDE_EFFECTIVE_PERIOD`. Nếu chưa tạo domain exception riêng,
application service phải translate nhất quán và có test cho error code.

Nên tạo `LicenseLifecyclePolicy` khi triển khai `reactivate` và `renew`, vì hai
use case này có cross-row/time rules. Policy chỉ **validate/derive decision**;
Entity mới thực hiện mutation:

```java
licenseLifecyclePolicy.requireCanReactivate(license, now);
license.activate(now);

RenewalDecision decision = licenseLifecyclePolicy.decideRenewal(
        sourceLicense,
        request.plan(),
        now
);
License renewed = License.issue(
        sourceLicense.getStation(),
        decision.plan(),
        decision.startAt()
);
```

Không để policy gọi `save()`, ghi audit event hoặc tự mở transaction. Những việc
đó thuộc application service.

### 7.2. State machine chuẩn

```text
PENDING   -> ACTIVE | CANCELLED
ACTIVE    -> SUSPENDED | CANCELLED | EXPIRED
SUSPENDED -> ACTIVE | CANCELLED | EXPIRED
CANCELLED -> terminal
EXPIRED   -> terminal

RENEW không phải transition; RENEW tạo một License row mới.
```

`PENDING -> EXPIRED` chưa thuộc phase hiện tại. Scheduler chạy trễ phải recovery
activation nếu `now` vẫn còn trong effective window. Chỉ bổ sung transition này
khi định nghĩa được business case “License chưa từng ACTIVE và toàn bộ effective
window đã trôi qua”, cùng audit/reconciliation contract tương ứng.

`ACTIVE` trong state machine vẫn phải đi cùng effective window:

```text
status == ACTIVE
AND startAt <= now
AND now < expiresAt
```

Nếu thời gian đã qua `expiresAt` nhưng scheduler chưa persist `EXPIRED`, command
không được coi row đó là usable chỉ vì cột status vẫn là `ACTIVE`.

### 7.3. Policy chi tiết theo API

| API | Preconditions | Mutation | Audit | Conflict chính |
| --- | --- | --- | --- | --- |
| `POST /{id}/suspend` | License tồn tại; `reason` hợp lệ; status `ACTIVE`; `now` còn trong effective window | `license.suspend(now)` và đặt Station `COMPLIANCE_HOLD` | License event `SUSPENDED`; control snapshot/audit lưu reason và actor | status sai, đã hết window, concurrent update |
| `POST /{id}/reactivate` | License `SUSPENDED` còn effective; Station không `REVOKED`; hold đã được phép release; không có ACTIVE khác | release hold rồi `license.reactivate(now)` trong cùng transaction | `REACTIVATED` và control release audit | hold chưa được release, ngoài thời hạn, active conflict |
| `POST /{id}/cancel` | Target thuộc `PENDING/ACTIVE/SUSPENDED`; không terminal | chỉ `license.cancel()` trên period được chọn; không đổi Licensing Control | `CANCELLED`, old status `-> CANCELLED`, actor ADMIN, reason | terminal state, concurrent update |
| `POST /{id}/renew` | Source effectively `ACTIVE` hoặc `EXPIRED`; Station control `CLEAR`; source latest; chưa có successor; plan hợp lệ | tạo successor mới, không sửa source | luôn `ISSUED`; thêm `ACTIVATED` nếu có hiệu lực ngay | hold/revoked, source sai status, duplicate successor, active conflict |
| `POST /stations/{stationId}/licensing/revoke` *(template)* | Station chưa `REVOKED`; reason hợp lệ | đặt control `REVOKED`; cancel các period non-terminal theo explicit use case | control audit `REVOKED` và License `CANCELLED` events tương ứng | concurrent update, đã revoked |

Policy về `reason`:

- `suspend`, `reactivate`, `cancel`: bắt buộc, trim trước khi ghi event.
- `renew`: không cần reason; đây là mua/tạo kỳ mới, không phải administrative
  status correction.
- Không ghi reason giả cho `ISSUED`, `ACTIVATED`, `EXPIRED` tự nhiên.

### 7.4. Renewal policy (final)

Phần này đã hợp nhất final review của renew và là nguồn sự thật duy nhất. Phase
hiện tại không mở rộng sang scheduled first issue, payment verification hoặc
manual first activation.

#### 7.4.1. Source nào được renew?

| Source | Kết luận | Hành vi |
| --- | --- | --- |
| `ACTIVE` và effectively active tại `now` | Cho phép | Tạo successor `PENDING`, `startAt = source.expiresAt`. |
| `EXPIRED` | Cho phép | Tạo successor bắt đầu tại `now` và activate ngay. |
| `SUSPENDED` | Từ chối | Không cho renew bypass suspension/compliance hold. |
| `PENDING` | Từ chối | Đây đã là kỳ tương lai chưa bắt đầu. |
| `CANCELLED` | Từ chối | Nếu muốn thiết lập lại quan hệ, dùng use case được phê duyệt mới. |

Một row còn persisted `ACTIVE` nhưng `expiresAt <= now` không được xem là
effectively active; expiry reconciliation sửa row sang `EXPIRED`, sau đó Admin
có thể retry.

#### 7.4.2. Invariant timeline và lineage

1. Renew luôn tạo một License row mới; không kéo dài `expiresAt`, đổi plan hoặc
   fee của source.
2. Source phải là period mới nhất của Station: `startAt DESC`, dùng `createdAt`
   làm tie-breaker. Status không quyết định “latest”.
3. Successor snapshot plan, fee, owner và có license code mới.
4. `renewedFrom = source` là invariant, không chỉ là audit metadata.
5. Mỗi source có tối đa một direct successor, kể cả successor cũ đã bị cancel.
6. Không renew một successor `PENDING`; phase này chỉ đặt trước tối đa một kỳ.

```text
Source A:  [Aug 01, Sep 01)
Renewed B: [Sep 01, Oct 01)

A -> B                 OK
A -> B và A -> C       REJECT
```

Hai lớp bảo vệ concurrent renew phải cùng tồn tại:

```text
Repository pre-check -> trả business error dễ hiểu
DB unique             -> chặn race giữa hai transaction
```

```sql
CREATE UNIQUE INDEX ux_licenses_renewed_from
    ON licenses (renewed_from_license_id)
    WHERE renewed_from_license_id IS NOT NULL;
```

Không dùng `UNIQUE(station_id, start_at)` làm renewal guard chính vì nó không
diễn đạt lineage và có thể trói nhầm một lần issue độc lập.

#### 7.4.3. Flow command renew

```text
renew(sourceLicenseId, requestedPlan)
  1. Capture một Instant now duy nhất
  2. Load source
  3. Require Station Licensing Control == CLEAR
  4. Validate source là period mới nhất
  5. Validate source status/effective window
  6. Validate source chưa có successor
  7. Chọn startAt:
       ACTIVE  -> source.expiresAt
       EXPIRED -> now
  8. Sinh licenseCode mới
  9. License.renewFrom(source, plan, startAt, licenseCode)
 10. Save successor PENDING và ghi ISSUED
 11. Nếu source EXPIRED:
       re-check control == CLEAR và không có ACTIVE khác
       activate successor tại now
       ghi ACTIVATED
 12. Flush để surface unique/optimistic conflict
 13. Trả về successor
```

Sequence cấp code có thể có gap khi transaction rollback. License code cần
unique và tăng đơn điệu, không cần liên tục tuyệt đối.

### 7.5. Station Licensing Control template

`LicenseStatus` mô tả một period; nó không đủ biểu diễn một quyết định tuân thủ
còn hiệu lực qua nhiều period. Template tối thiểu là một control snapshot cho
mỗi Station:

```text
StationLicensingControl
  stationId   UNIQUE/FK
  state       CLEAR | COMPLIANCE_HOLD | REVOKED
  reason      VARCHAR(500)
  changedAt   Instant
  changedBy   UUID
  version     optimistic @Version
```

| Control state | Ý nghĩa | Issue/Renew/Reactivate/Scheduled activation |
| --- | --- | --- |
| `CLEAR` | Không có chặn cấp phép | Cho policy tiếp tục kiểm tra các điều kiện khác. |
| `COMPLIANCE_HOLD` | Tạm dừng xuyên kỳ vì an toàn/pháp lý/tuân thủ | Chặn. Scheduler giữ successor ở `PENDING` và retry ở vòng sau. |
| `REVOKED` | Quan hệ cấp phép của Station đã chấm dứt | Chặn toàn bộ; không tự clear. |

Seam code nên nhỏ và read-only đối với lifecycle policy:

```java
public interface StationLicensingControlPolicy {
    void requireClearForIssue(UUID stationId);
    void requireClearForRenew(UUID stationId);
    void requireClearForActivation(UUID stationId);
}
```

Mutation control thuộc application use case riêng, không đặt trong validation
policy. Template flow:

```text
suspend current License
  -> License ACTIVE -> SUSPENDED
  -> control CLEAR -> COMPLIANCE_HOLD

release hold/reactivate
  -> verify remediation
  -> control COMPLIANCE_HOLD -> CLEAR
  -> reactivate current License nếu nó vẫn còn effective

source hết hạn trong lúc hold
  -> source có thể EXPIRED
  -> control vẫn COMPLIANCE_HOLD
  -> successor tuyệt đối chưa được ACTIVE
```

Phase đầu có thể chỉ lưu current snapshot. Khi cần audit compliance hoàn chỉnh,
bổ sung append-only control event (`HOLD_PLACED`, `HOLD_RELEASED`, `REVOKED`);
không nhét các event này vào `LicenseStatusEvent` vì chúng áp dụng cho Station,
không phải riêng một License period.

### 7.6. Cancel period và revoke relationship

Hai command có semantics khác nhau và không được dùng chung một tên mơ hồ:

| Command | User-facing wording | Phạm vi | Successor tương lai |
| --- | --- | --- | --- |
| `cancelLicense(licenseId)` | **Terminate this license period only** | Chỉ row được chọn chuyển `CANCELLED`. | Không tự đổi. Nếu control `CLEAR`, successor `PENDING` vẫn có thể ACTIVE đúng hạn. |
| `revokeStationLicensing(stationId)` | **Revoke the station's licensing relationship / stop future operation** | Control chuyển `REVOKED`; explicit use case xử lý các period non-terminal. | Bị cancel hoặc không bao giờ được activate theo revoke contract. |

Do đó câu “Tôi cancel License rồi, sao tháng sau nó tự chạy lại?” phải được trả
lời ngay trên UI/API contract: cancel period không phải revoke Station. Với
revoke, transaction tối thiểu phải:

```text
1. Lock bằng optimistic version/control invariant
2. Set control REVOKED và lưu reason/actor/time
3. Cancel current ACTIVE/SUSPENDED period nếu có
4. Cancel successor PENDING nếu có
5. Ghi audit cho từng mutation
6. Flush; toàn bộ commit/rollback cùng nhau
```

Không tự động biến successor thành `SUSPENDED`: successor chưa từng ACTIVE nên
transition đúng khi chấm dứt là `PENDING -> CANCELLED`.

### 7.7. Pending-renewal activation và recovery

Early renew tạo successor `PENDING`; scheduler activation là phần bắt buộc của
renew, không phải future enhancement.

Candidate query bình thường:

```text
status == PENDING
AND renewedFrom IS NOT NULL
AND startAt <= now
AND now < expiresAt
```

Trong `REQUIRES_NEW` transaction của từng candidate:

```text
1. Reload và re-check successor PENDING/effective window/renewedFrom
2. Require Station Licensing Control == CLEAR
3. Đảm bảo source period đã kết thúc
4. Expire source nếu cần và ghi SYSTEM EXPIRED event
5. Check Station không có ACTIVE License khác
6. Activate successor và ghi SYSTEM ACTIVATED event
7. Flush
```

Nếu control là `COMPLIANCE_HOLD`, candidate **không bị cancel hoặc expired**;
service trả `blocked/skipped`, scheduler ghi metric/log và retry ở vòng sau. Nếu
hold được release khi successor vẫn còn trong effective window, vòng sau phải
activate được dù `startAt` đã qua — đó là recovery, không phải business expiry.

Nếu `now >= expiresAt` và successor chưa từng ACTIVE, phase hiện tại không tự
đổi `PENDING -> EXPIRED`. Ghi reconciliation anomaly để Admin xử lý. Chỉ thêm
transition này sau khi định nghĩa được business case, audit event và tác động
fee/refund tương ứng.

Scheduler chỉ query ID theo batch; mỗi candidate dùng transaction riêng. Một
optimistic/unique conflict rollback candidate hiện tại nhưng không dừng toàn
batch. Expiration scheduler chạy trước hay sau không được làm thay đổi kết quả
rollover.

### 7.8. Transaction, audit và concurrency contract

Mỗi command chạy trong một transaction:

```text
authorize/validate request
-> load License
-> evaluate lifecycle policy
-> remember fromStatus
-> invoke Entity method hoặc tạo row mới
-> append LicenseStatusEvent
-> flush
-> commit
```

- Không `catch` rồi nuốt `DataIntegrityViolationException` hoặc
  `OptimisticLockingFailureException`.
- Cùng một License: dùng `@Version` và map stale update thành
  `LICENSE_WAS_MODIFIED` (`409`). Không cần vừa pessimistic lock vừa optimistic
  lock nếu không có lý do đã được đo/kiểm chứng.
- Nhiều License của cùng Station: repository pre-check cho lỗi dễ hiểu, nhưng DB
  unique constraint vẫn là final guard.
- Event và mutation phải commit/rollback cùng nhau.
- `LicenseStatusEventType.supports(from, to)` là audit consistency guard, không
  thay thế Entity state machine hoặc lifecycle policy.

### 7.9. Error mapping tối thiểu

| Tình huống | HTTP |
| --- | --- |
| License không tồn tại | `404` |
| Sai transition/state terminal | `409 INVALID_STATUS_TRANSITION` |
| Reactivate/suspend ngoài effective window | `409 LICENSE_OUTSIDE_EFFECTIVE_PERIOD` |
| Có active License khác hoặc DB unique conflict | `409 ACTIVE_LICENSE_ALREADY_EXISTS` |
| Station đang compliance hold | `409 STATION_LICENSING_ON_HOLD` |
| Quan hệ cấp phép đã bị revoke | `409 STATION_LICENSING_REVOKED` |
| Concurrent stale update | `409 LICENSE_WAS_MODIFIED` |
| Reason/plan request không hợp lệ | `400` |
| Không phải ADMIN | `403` |

## 8. Checklist Tự kiểm tra & Ma trận Trạng thái / Audit Events

### 8.1. Ma trận Chuyển đổi Trạng thái & Audit Events

| Thao tác | Trạng thái nguồn | Trạng thái đích | Đối tượng áp dụng | Event Type | Actor | Lý do (`reason`) | Preset Chips (UI) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`issue`** | `null` | `PENDING` $\rightarrow$ `ACTIVE` | **License Mới** | `ISSUED` | `USER` | Không (`null`) | Không |
| **`suspend`** | `ACTIVE` | `SUSPENDED` | License hiện tại | `SUSPENDED` | `USER` | **BẮT BUỘC** (`@NotBlank`) | **Có** (Authority) |
| **`reactivate`**| `SUSPENDED` | `ACTIVE` | License hiện tại | `REACTIVATED` | `USER` | **BẮT BUỘC** (`@NotBlank`) | **Có** (Authority) |
| **`cancel`** | `ACTIVE`/`PENDING`/`SUSPENDED` | `CANCELLED` | License hiện tại | `CANCELLED` | `USER` | **BẮT BUỘC** (`@NotBlank`) | **Có** (Authority) |
| **`expire` (tự động)** | `ACTIVE`/`SUSPENDED` | `EXPIRED` | License hiện tại | `EXPIRED` | `SYSTEM` | Không (`null`) | Không |
| **`renew`** | Giữ nguyên kỳ cũ | Tạo License Mới (`PENDING`/`ACTIVE`) | **License Mới tạo** | `ISSUED` | `USER` | Không (`null`) | Không |
| **`place compliance hold`** | `CLEAR` | `COMPLIANCE_HOLD` | Station Licensing Control | Control audit *(future)* | `USER` | **BẮT BUỘC** | Có |
| **`release hold`** | `COMPLIANCE_HOLD` | `CLEAR` | Station Licensing Control | Control audit *(future)* | `USER` | **BẮT BUỘC** | Có |
| **`revoke relationship`** | `CLEAR`/`COMPLIANCE_HOLD` | `REVOKED` | Station Licensing Control + các License non-terminal | Control audit + `CANCELLED` | `USER` | **BẮT BUỘC** | Có |

> ⚠️ **Lưu ý cốt lõi**: `renew` là thao tác **Append-Only Subscription Period** tạo ra bản ghi License mới. Tuyệt đối **không chuyển trạng thái License cũ sang `RENEWED`**.

### 8.2. Checklist Kiểm tra Kỹ thuật

- [x] **Dynamic JPA Specifications**: `LicenseSpecification.filter(filter)` hỗ trợ cả Global Search và các trường chi tiết; tự động bỏ qua các trường `null` để không sinh SQL thừa; có integration test đầy đủ.
- [ ] **MapStruct Clean Mappings**: Khai báo các mapper với `@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)` và tính toán chính xác `daysLeft`, `expiringSoon` (dựa trên effectively active).
- [ ] **Tách biệt Controller**: `AdminLicenseQueryController` (tra cứu) và `AdminLicenseController` (commands & state transitions).
- [ ] **Đầy đủ 2 Tab History cho Frontend**:
  - `GET /api/v1/admin/licenses/{licenseId}/status-events` $\rightarrow$ Trả về sự kiện của *chính License đó* (sắp xếp `performedAt DESC`).
  - `GET /api/v1/stations/{stationId}/licenses` $\rightarrow$ Trả về tất cả các kỳ của *trạm sạc đó* (sắp xếp `startAt DESC`).
- [ ] **Audit Actor Invariant**: Khi `actorType = USER`, `performedBy` bắt buộc phải là profile hợp lệ của Admin. Khi `actorType = SYSTEM`, `performedByName` là `"SYSTEM"`.
- [x] **Renewal lineage guard**: `renewed_from_license_id` unique và mỗi source tối đa một successor.
- [x] **Pending renewal scheduler seam**: activation chạy theo batch, re-check từng candidate và dùng transaction riêng.
- [ ] **Station Licensing Control**: thêm snapshot `CLEAR/COMPLIANCE_HOLD/REVOKED`, optimistic version và policy query.
- [ ] **Suspend/Reactivate integration**: suspend đặt hold; release/reactivate clear hold theo cùng transaction contract.
- [ ] **Scheduler hold guard**: successor bị hold giữ `PENDING` và được retry/recovery sau khi release.
- [ ] **Revoke use case riêng**: không overload `cancelLicense`; revoke phải chặn future operation và xử lý các period non-terminal.
- [ ] **Pending anomaly handling**: không auto `PENDING -> EXPIRED` cho đến khi có business/audit/fee policy đầy đủ.
