# E2 Implementation Checkpoint

> Cập nhật gần nhất: 2026-08-19  
> Phạm vi: E2 — Station, approval, license, provisioning và QR  
> Mục đích: phản ánh đúng code hiện tại và định hướng công việc từ T15 đến T20.

## 1. Kết luận hiện tại

T15 **chưa nên được xem là Done hoàn toàn** theo Acceptance Criteria trong
Backlog. Phần issue license và nền tảng lifecycle đã hoạt động, nhưng một số
API/query/transition vẫn còn skeleton.

Điểm bắt đầu hợp lý trong hai ngày tới:

```text
T15 hardening
    -> chốt policy eligibility dùng chung
    -> T17 provisioning vertical slice
    -> QR token foundation
```

Không nên cố hoàn thành đồng thời T16–T20 trong hai ngày. T18 QR image,
T19 integration vào public discovery/booking và T20 owner runtime management
nên đi sau khi provisioning aggregate đã ổn định.

## 2. Trạng thái thực tế của T12–T20

| Task | Trạng thái thực tế | Kết luận |
| --- | --- | --- |
| T12 — Owner đăng ký trạm | Done | Tạo station `PENDING_APPROVAL`, owner từ JWT, validate profile/location và ghi `SUBMITTED`. |
| T13 — Location/coordinates | Done | Province/ward và latitude/longitude đã có; PostGIS proximity để T26. |
| T14 — Admin approve/reject | Done ở backend | Approve/reject, history, optimistic locking và active-license check đã có test. |
| T15 — License lifecycle | In progress, chưa Done | Issue/activate, pricing, audit event, unique guard và search specification đã có. Query API, owner API, suspend/cancel/reactivate/renew vẫn chưa hoàn chỉnh. |
| T16 — Expiry/reminder/visibility | Partial | Expiry scheduler và event `EXPIRED` đã có. Reminder chưa có; public/booking gate chưa có nơi tích hợp vì T25/T30 chưa implement. |
| T17 — Provision ChargePoint/Connector | Foundation only | Entity/schema/repository có; chưa có aggregate command, DTO, service, controller hoặc integration test. |
| T18 — QR connector | Foundation only | `qr_token` có trong schema/entity; chưa có contract token, QR payload/image hoặc download API. |
| T19 — Readiness/activation | Cần đổi scope | Không nên đồng bộ ChargePoint status theo License. Cần tách hardware activation khỏi driver/business eligibility. |
| T20 — Owner charger management | To do | Chỉ nên bắt đầu sau khi T17 chốt ownership, hardware fields và runtime transition. |

## 3. Phần T15 đã có

- `License` entity với calendar-based `MONTHLY`/`YEARLY`.
- `feeAmount` lấy từ `Plan` và lưu snapshot.
- Issue license theo transaction.
- Ghi `ISSUED` và `ACTIVATED` status event.
- Effective-active query:

```text
status == ACTIVE
AND startAt <= now
AND now < expiresAt
```

- Unique database guard: tối đa một license `ACTIVE` cho mỗi station.
- Translate đúng active-license constraint conflict.
- Optimistic version trên License.
- Expiry scheduler theo batch, idempotent và chịu concurrent update.
- `LicenseSpecification` compose theo nhóm search/license/station/owner.
- Prefix search cho `licenseCode` và `stationCode`.
- Integration test cho specification và unit test cho issue/expiry.

## 4. T15 còn thiếu trước khi đánh dấu Done

### 4.1 Lifecycle commands

- `suspendLicense()` phải transition thật và ghi event; không được catch rồi
  nuốt exception.
- `cancelLicense()` và `reactivateLicense()` phải có service method riêng.
- `renewLicense()` phải tạo License row mới, không mutate kỳ cũ.
- Mỗi transition phải validate state, effective window và optimistic conflict.
- Controller không được gọi chung `suspendLicense()` cho cancel/reactivate/renew.

Không thay toàn bộ transition bằng một policy class. Cách chia trách nhiệm được
chốt như sau:

```text
License entity
  -> bảo vệ state transition và invariant nội tại

LicenseLifecyclePolicy/domain service
  -> rule cần repository/time/các License khác, chủ yếu reactivate và renew

LicenseService
  -> transaction, authorization context, load/save, audit event, error mapping

Database
  -> optimistic version và unique constraints
```

Canonical state machine, precondition của từng Admin API, renewal semantics và
concurrency contract nằm tại
[Admin License Lifecycle Policy](./license-admin-backend-api-design.md#7-admin-license-lifecycle-policy).

Đặc biệt, early renew tạo một row `PENDING`; phải có pending-activation job khi
`startAt` tới hạn. Nếu thiếu job này thì renew chưa hoàn chỉnh dù API đã trả
`201 Created`.

### 4.2 Query/read side

- Admin search trả page response thật, không trả `null`.
- License detail.
- Status-event timeline của một license.
- History tất cả kỳ license của một station.
- Owner xem license chỉ trong station mình sở hữu.

### 4.3 Verification

- Controller/service tests cho tất cả lifecycle commands.
- Repository integration test cho owner scope và history ordering.
- E2E tối thiểu:

```text
register station
-> issue license
-> approve station
-> owner reads station/license
-> suspend/reactivate/cancel
-> renew creates a new row
```

## 5. Ba trục trạng thái phải tách biệt

Không dùng một enum `ACTIVE` để đại diện cho toàn bộ hệ thống.

| Trục | Ý nghĩa |
| --- | --- |
| `Station.status` | Hồ sơ/trạm đã được platform duyệt hay bị suspend/reject. |
| `License` | Owner có entitlement để nhận business mới tại thời điểm hiện tại hay không. |
| `ChargePoint.provisioningStatus` | Hardware đã được platform provision/activate hay chưa. |
| `Connector.runtimeStatus` | Connector hiện có thể nhận phiên sử dụng mới hay đang offline/in-use. |

Các trạng thái sau đều hợp lệ:

```text
Station ACTIVE + License SUSPENDED + ChargePoint ACTIVE
Station ACTIVE + License ACTIVE + ChargePoint UNCLAIMED
Station ACTIVE + License ACTIVE + ChargePoint ACTIVE + Connector OFFLINE
```

Chúng không được tự động ghi đè lẫn nhau.

## 6. Driver/business eligibility

### 6.1 Station xuất hiện trong discovery

```text
station.status == ACTIVE
AND có License effectively active tại now
AND có ít nhất một ChargePoint provisioningStatus == ACTIVE
AND có ít nhất một Connector runtimeStatus == AVAILABLE
```

T25/T27 phải áp dụng rule này khi search/map.

### 6.2 Tạo booking mới

`createBooking()` ở T30 phải kiểm tra lại trên connector cụ thể:

```text
station ACTIVE
AND License effectively active
AND chargePoint ACTIVE
AND connector AVAILABLE
AND nằm trong operating hours
AND có pricing/policy hợp lệ
AND slot chưa bị chiếm
```

UI ẩn station chỉ là UX. Backend booking command mới là boundary bảo vệ
nghiệp vụ.

### 6.3 Khi License mất hiệu lực

- Không đổi `Station.status`.
- Không đổi `ChargePoint.provisioningStatus`.
- Không đổi `Connector.runtimeStatus`.
- Ẩn station khỏi discovery mới.
- Từ chối booking mới.
- Giữ booking confirmed/paid đã tồn tại và session đang chạy.

## 7. Điều chỉnh scope T16–T20

### T16 — License expiry, reminder và eligibility foundation

Tách thành ba phần:

1. **T16A Expiry reconciliation — Done**
   - Scheduler tìm license đến hạn.
   - Transition sang `EXPIRED`.
   - Ghi system event.
   - Idempotent và xử lý optimistic conflict.
2. **T16B Reminder — Deferred**
   - Chưa nên tạo notification event khi notification module/contract chưa có.
   - Có thể tạo task riêng khi E9 notification bắt đầu.
3. **T16C Eligibility foundation — Next**
   - Chốt một policy/query contract dùng chung cho discovery và booking.
   - Chưa cần tạo public endpoint trước T25/T30.

### T17 — Admin provision ChargePoint + Connector

Một command transaction nên tạo cả aggregate:

```text
Station
  -> ChargePoint
      -> one or more Connector
```

Acceptance Criteria:

- Chỉ ADMIN được provision.
- Station tồn tại và chưa bị soft-delete.
- Charge-point code unique.
- Connector code unique trong phạm vi ChargePoint.
- Validate connector type, charger type, power và slot minutes.
- Hardware fields không do Owner sửa sau provisioning.
- Connector bắt đầu `OFFLINE`.
- QR token sinh một lần và không đổi.
- Transaction fail toàn bộ nếu một connector không hợp lệ.

Không dùng License để mutate provisioning status.

### T18 — QR token/image

Tách làm hai bước:

1. Token foundation đi cùng T17:
   - `qrToken` random, unique, immutable.
   - QR payload chỉ chứa opaque token hoặc URL check-in, không chứa internal ID
     nhạy cảm.
2. QR image/download đi sau:
   - Render PNG/SVG từ payload.
   - Admin/Owner có quyền download theo ownership.
   - Không regenerate token khi download.

### T19 — Hardware activation và operational readiness

Đổi ý nghĩa task:

- `UNCLAIMED -> ACTIVE` là hardware/provisioning transition.
- Điều kiện transition: ChargePoint thuộc station hợp lệ và có ít nhất một
  Connector cấu hình hợp lệ.
- Có thể yêu cầu Station đã được approve nếu muốn ngăn activate hardware cho
  hồ sơ chưa duyệt.
- **Không dùng active License làm nguồn sự thật của provisioning status.**
- Public/bookable readiness luôn là derived policy tại point of use.

Nếu vẫn muốn kiểm tra License tại thời điểm activate, đó chỉ là one-time
business precondition; nó không thay thế dynamic license check ở discovery và
booking.

### T20 — Owner quản lý charger

- Owner/staff phải được scope theo station ownership/assignment.
- Cho phép sửa display name và zone label.
- Cho phép đổi Connector runtime giữa `AVAILABLE` và `OFFLINE` theo policy.
- Không cho sửa charge-point code, connector code, type, power, slot minutes,
  QR token hoặc provisioning status.
- Chặn `OFFLINE` khi có booking/session active được thực hiện ở T21.

## 8. Kế hoạch hai ngày đề xuất

### Ngày 1 — Đóng T15 thật sự

#### Buổi sáng

1. Chốt API contract hiện tại; loại các endpoint skeleton trả `null`.
2. Implement suspend/cancel/reactivate với state validation và audit event.
3. Implement renew bằng License row mới.

#### Buổi chiều

4. Hoàn thiện admin search/detail/status-events/station history.
5. Hoàn thiện Owner read API với ownership scope.
6. Test lifecycle, owner scope, renewal và E2E
   `issue -> approve -> read -> suspend/reactivate`.

**Exit gate ngày 1:** chỉ đánh dấu T15 Done khi không còn controller/service
skeleton và acceptance criteria của Backlog đều có test hoặc verification.

### Ngày 2 — Eligibility foundation + provisioning vertical slice

#### Buổi sáng

1. Chốt policy eligibility dùng chung:
   Station + effective License + ChargePoint + Connector.
2. Viết query/policy test cho các combination quan trọng.
3. Ghi rõ integration point tương lai ở T25 và T30; chưa dựng public API sớm.

#### Buổi chiều

4. Implement một transaction admin provision ChargePoint cùng Connector list.
5. Sinh immutable QR token ngay khi tạo Connector.
6. Thêm uniqueness constraints/index cần thiết bằng Flyway migration mới.
7. Integration test happy path, duplicate code, invalid connector và rollback.

**Exit gate ngày 2:** một ChargePoint aggregate provision được end-to-end qua
API, token ổn định, test xanh; chưa cần QR image, public discovery hay Owner
runtime management.

## 9. Thứ tự sau hai ngày

```text
T18 QR image/download
-> T19 hardware activation command
-> T20 owner display/runtime management
-> T21 active-booking guard
-> T22/T23 operating hours + pricing
-> T25 discovery integrates eligibility
-> T30 createBooking re-checks eligibility
```

## 10. Điều chỉnh Backlog đề xuất

| Task | Status đề xuất | Progress đề xuất | Ghi chú |
| --- | --- | --- | --- |
| T15 | In Progress | 60% | Issue/expiry foundation có; lifecycle/query/owner read còn thiếu. |
| T16 | In Progress | 60% | Expiry done; reminder deferred; eligibility integration chưa có. |
| T17 | In Progress | 30% | Entity/repository/schema có; aggregate API/service/test chưa có. |
| T18 | In Progress | 15% | Token column/default có; contract/image/download chưa có. |
| T19 | To Do | 0% | Đổi scope thành hardware activation + derived readiness. |
| T20 | To Do | 0% | Bắt đầu sau T17/T19. |

## 11. Tài liệu liên quan

- [Admin License backend design](./license-admin-backend-api-design.md)
- [License–Station eligibility policy](./license-station-eligibility-policy.vi.md)
- [License frontend integration handoff](./license-frontend-integration-handoff.md)

## 12. Cách cập nhật checkpoint

Sau mỗi task:

1. Đối chiếu status với code, không chỉ với phần trăm trên tracker.
2. Ghi endpoint/use case và test đã hoàn thành.
3. Không đánh dấu Done khi controller/service còn trả `null` hoặc method trống.
4. Ghi lại domain decision và lý do.
5. Cập nhật ngày ở đầu file.
