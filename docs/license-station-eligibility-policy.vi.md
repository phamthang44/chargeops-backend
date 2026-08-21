# Chính sách điều kiện hoạt động giữa License và Station

> **Trạng thái:** Quyết định business đã được chấp nhận  
> **Ngày hiệu lực:** 2026-08-17  
> **Đối tượng đọc:** Backend, Frontend, Product/BA và QA  
> **Phạm vi:** Mối quan hệ giữa trạng thái Station, quyền sử dụng từ License, việc hiển thị Station cho Driver và việc tạo booking mới.  
> **Ưu tiên quyết định:** Tài liệu này thay thế kết luận trước đây về việc đồng bộ trạng thái Station–License trong `docs/race-condition-analysis.md`. Các phát hiện technical còn lại trong tài liệu đó vẫn có giá trị.

## 1. Mục đích

Tài liệu này là nguồn tham chiếu chính để quyết định một Station có được phép nhận **business mới thông qua ChargeOps** hay không. Nó giải quyết sự nhập nhằng giữa hai khái niệm:

- Station đã được platform phê duyệt và đang ở trạng thái hoạt động.
- Owner của Station hiện có License hợp lệ để tiếp tục nhận business mới trên platform.

Tài liệu này **không** định nghĩa charger availability, operating hours, payment/refund, hay các tình huống safety. Những rule đó có thể bổ sung thêm điều kiện eligibility sau này.

---

## 2. Lịch sử quyết định: vì sao policy này được hình thành

Phần này cố ý ghi lại toàn bộ quá trình suy nghĩ, kể cả những hướng ban đầu nghe hợp lý nhưng cuối cùng không chọn. Mục đích là để sau này không chỉ nhớ **quyết định cuối**, mà còn nhớ **problem business/technical nào đã dẫn tới quyết định đó**.

### 2.1 Điểm bắt đầu: cross-entity race condition

Cuộc thảo luận ban đầu xuất phát từ concurrency chứ chưa phải từ business policy.

Case cụ thể:

```text
Admin A approve Station S1
Admin B suspend/cancel License L1 của S1
gần như cùng một thời điểm
```

Có thể xảy ra:

```text
T1  Admin A đọc Station = PENDING_APPROVAL
T2  Admin A kiểm tra License = ACTIVE
T3  Admin B đổi License ACTIVE -> SUSPENDED
T4  Admin A commit Station PENDING_APPROVAL -> ACTIVE
```

Kết quả cuối DB:

```text
Station.status = ACTIVE
License.status = SUSPENDED
```

Lúc đầu trạng thái này trông giống một lỗi business rất rõ.

Suy nghĩ tự nhiên ban đầu là:

> Nếu License là quyền cho Station hoạt động/kinh doanh thông qua ChargeOps, License bị suspend thì Station cũng nên bị suspend hoặc inactive.

Theo cách hiểu đó:

```text
Station ACTIVE + License SUSPENDED
```

là trạng thái không hợp lệ.

Từ đó xuất hiện hướng technical ban đầu:

```text
Pessimistic lock Station
        +
re-check state sau khi lấy lock
        +
nếu admin khác đã thay đổi dữ liệu
thì request còn lại báo conflict và rollback
```

Ví dụ UX có thể là:

```text
"Dữ liệu vừa được thay đổi bởi một administrator khác.
Vui lòng reload và thử lại."
```

Hướng này technically defend được. Tuy nhiên, nó dựa trên một giả định business chưa được chốt:

> Station ACTIVE có bắt buộc License phải ACTIVE ở mọi thời điểm hay không?

Đây chính là câu hỏi làm thay đổi toàn bộ hướng thiết kế sau đó.

#### Vì sao `@Version` đơn lẻ không giải quyết race ban đầu?

`@Version` hoạt động tốt khi hai transaction cùng update **một row đã tồn tại**.

Ví dụ:

```text
Admin A đọc License version = 5
Admin B đọc License version = 5

A: suspend License
B: cancel License
```

Cả hai tranh cùng `licenses.version`, nên một request có thể thắng và request còn lại bị optimistic-lock conflict.

Nhưng race Station–License lại khác:

```text
Transaction A update Station
Transaction B update License
```

Mỗi transaction update một row khác nhau.

Vì vậy:

```text
Station.version
```

và:

```text
License.version
```

không tự biết rằng state kết hợp giữa hai entity đã thay đổi.

Đó là lý do pessimistic locking trên một resource chung từng được cân nhắc.

Tương tự, đặt `@Version` trên `LicenseStatusEvent` append-only cũng không giải quyết được. Hai event mới là hai row mới, không tranh update cùng một row cũ.

Điểm rút ra:

> Không nên chọn optimistic/pessimistic/atomic update chỉ vì nghe thấy từ “concurrency”. Trước tiên phải biết chính xác business rule nào có nguy cơ bị phá.

#### Một race khác lại có solution khác: concurrent License issue

Trong cùng quá trình review còn có case:

```text
Admin A issue License cho Station S1
Admin B cũng issue License cho S1
cùng lúc
```

Cả hai có thể cùng:

```text
exists ACTIVE License? -> false
```

trước khi transaction kia commit.

Nhưng ở đây business rule rất rõ:

> Một Station chỉ được có tối đa một License ACTIVE.

DB đã có partial unique index để bảo vệ rule này.

Vì vậy solution được chọn:

```text
application pre-check
        +
DB partial unique constraint là final guard
        +
catch đúng constraint violation
        ↓
HTTP 409 / ACTIVE_LICENSE_ALREADY_EXISTS
```

Không cần mặc định thêm pessimistic locking.

Điều này cũng tạo ra một nguyên tắc quan trọng:

> Hai race condition khác nhau hoàn toàn có thể cần hai cách bảo vệ khác nhau.

---

### 2.2 Hướng business đầu tiên: đồng bộ trạng thái Station và License

Mô hình đầu tiên được nghĩ tới khá đơn giản:

```text
License ACTIVE
→ Station có thể ACTIVE

License SUSPENDED
→ Station SUSPENDED/INACTIVE

License EXPIRED
→ Station SUSPENDED/INACTIVE

License được reactivate/renew
→ Station ACTIVE trở lại
```

Hướng này hấp dẫn vì:

- Dễ hiểu.
- Dễ nhìn state.
- Dễ giải thích khi defend.
- Driver chỉ cần nhìn Station status.
- Có vẻ giảm validation ở nhiều nơi.

Nôm na:

> Không có License hợp lệ thì Station không được hoạt động.

Tuy nhiên, khi đi tiếp vào các tình huống business thực tế, mô hình này bắt đầu sinh ra nhiều câu hỏi.

#### Vấn đề 1: Station có thể bị unavailable vì lý do khác License

Ví dụ tương lai Station có thể:

- bị admin suspend,
- bị reject,
- owner withdraw,
- maintenance,
- có vấn đề safety,
- có vấn đề hardware,
- hoặc một lý do operational khác.

Nếu License expired cũng tự đổi Station sang một status không hoạt động thì sau này rất khó biết:

```text
Station đang không hoạt động vì License?
hay vì chính Station có vấn đề?
```

#### Vấn đề 2: reverse transition trở nên khó hiểu

Giả sử:

```text
License EXPIRED
→ Station bị chuyển khỏi ACTIVE
```

Sau đó Owner renew License.

Có tự động:

```text
Station -> ACTIVE
```

không?

Nếu Station trước đó đã bị admin suspend vì lý do khác thì sao?

Nếu tự ACTIVE lại chỉ vì License renew thì có thể vô tình mở lại một Station đáng lẽ vẫn phải bị khóa.

Muốn giải quyết sạch thì phải lưu thêm nguyên nhân hoặc tạo thêm state/rule.

Như vậy cái tưởng là “đồng bộ cho đơn giản” lại bắt đầu coupling hai state machine với nhau.

#### Vấn đề 3: expiry theo thời gian kéo theo synchronization

License có `expiresAt`.

Nếu tới giờ hết hạn mà muốn Station status lập tức đổi theo thì cần:

```text
scheduler / cron / event / reconciliation
```

để update Station.

Sau đó renew/reactivate lại phải có cơ chế reverse.

Tức là hệ thống phải duy trì hai state luôn khớp nhau chỉ vì License thay đổi.

Đây là complexity mà project hiện tại chưa chắc cần.

---

### 2.3 Câu hỏi business tiếp theo: Owner quên renew thì Station có nên bị coi là hỏng?

Sau đó xuất hiện một câu hỏi thực tế hơn.

Owner có thể:

- quên renew,
- chậm thanh toán,
- đang xử lý một vấn đề hành chính,
- chưa kịp mua lại gói,
- hoặc có một lý do tạm thời khác khiến License hết hiệu lực.

Nếu chỉ vì vậy mà Station bị đổi sang một trạng thái giống như chính Station bị lỗi/suspend thì hơi mạnh tay.

Điều này dẫn tới việc tách hai ý nghĩa:

```text
Station status
= trạng thái approval/operational của chính Station

License status
= entitlement/quyền hiện tại của Owner
  để nhận business mới thông qua ChargeOps
```

Khi hai khái niệm được tách ra, state này:

```text
Station.status = ACTIVE
License.status = SUSPENDED
```

không còn nhất thiết là data sai.

Nó có thể có nghĩa:

> Station vẫn là một Station đã được platform approve và vẫn tồn tại bình thường, nhưng Owner hiện tạm thời không được phép nhận business mới qua ChargeOps.

Owner/Admin vẫn cần thấy Station để:

- quản lý,
- xem thông tin,
- renew/reactivate License,
- xem history,
- xử lý vấn đề liên quan.

Chỉ Driver-facing commercial eligibility bị ảnh hưởng.

Đây là bước làm cho cross-entity race ban đầu đổi ý nghĩa.

Race vẫn tồn tại về technical, nhưng kết quả `Station ACTIVE + License SUSPENDED` không còn tự động được xem là business bug.

---

### 2.4 Grace period từng được cân nhắc nhưng bỏ khỏi scope

Một hướng mềm hơn từng được nghĩ tới là grace period:

```text
License hết hạn
→ cho Owner thêm vài ngày
→ Station vẫn nhận business
→ cảnh báo Owner
→ hết grace mà chưa renew mới block
```

Hướng này có vẻ thân thiện với Owner, đặc biệt trong case quên renew.

Nhưng nó lập tức tạo thêm hàng loạt câu hỏi:

- Grace bao nhiêu ngày?
- Trong grace có được nhận booking mới không?
- Booking được tạo trong grace nhưng lịch sạc nằm sau ngày grace thì sao?
- Cảnh báo Owner lúc nào và bao nhiêu lần?
- `SUSPENDED` có grace không hay chỉ `EXPIRED`?
- Grace có ảnh hưởng renewal date không?
- Có ảnh hưởng payment/reporting không?

Đây đều là business question hợp lệ, nhưng hiện tại không có đủ requirement để trả lời một cách có cơ sở.

Nếu tự đặt con số/rule chỉ để hoàn thành FYP thì lại biến thành business policy bịa thêm.

Vì vậy quyết định:

> Grace period là một khả năng tương lai, nhưng **không nằm trong scope hiện tại**.

---

### 2.5 Hướng thiết kế thứ hai: không sync status, tính eligibility tại point of use

Sau khi tách ý nghĩa Station và License, một hướng đơn giản hơn xuất hiện:

```text
Station giữ state riêng.
License giữ state riêng.
Khi Driver muốn tạo business mới,
hệ thống tính xem Station có đủ điều kiện hay không.
```

Rule cơ bản:

```text
Driver eligible
=
Station ACTIVE
AND License effectively ACTIVE
```

Điều này cần được check ở những nơi business thực sự được sử dụng, ví dụ:

```text
Driver search/map
Create booking
các entry point tương lai tạo business mới
```

Ẩn Station ở frontend chỉ là UX.

Backend vẫn phải re-check tại `createBooking()`, vì Driver/client có thể gọi API trực tiếp.

Như vậy:

```text
License SUSPENDED/EXPIRED
→ không cần mutate Station.status
→ chỉ làm Station không còn eligible cho business mới
```

Ưu điểm:

- Không cần sync hai state machine.
- Không cần event chỉ để đổi Station status theo License.
- Không cần cron đổi Station status vì License expiry.
- Renew/reactivate License không phải đoán xem có nên restore Station status không.
- `Station ACTIVE + License SUSPENDED` được phép tồn tại rõ ràng.

---

### 2.6 Trade-off: validation tại point of use cực hơn một chút

Hướng này không miễn phí.

Trước đây có thể chỉ cần:

```text
station.status == ACTIVE
```

Bây giờ muốn biết Driver có dùng Station cho business mới được không phải kiểm tra thêm License.

Ví dụ:

```text
Station ACTIVE?
        +
License effectively active?
        ↓
ALLOW / DENY
```

Nếu copy điều kiện này thủ công khắp nơi thì dễ sinh bug:

```text
search check đúng
booking check khác
detail quên check License
map dùng query khác
```

Vì vậy mitigation là:

> Không rải rule khắp code. Gom eligibility thành một policy/query dùng chung.

Ví dụ về mặt concept:

```text
Station status
        +
License usability
        ↓
Driver eligibility policy
```

Search/map và create booking phải dựa trên cùng một định nghĩa.

Đây là trade-off được chấp nhận:

> Validation nhiều hơn một chút, nhưng đổi lại tránh được lifecycle synchronization phức tạp hơn rất nhiều.

---

### 2.7 Một vấn đề khác được phát hiện: “License ACTIVE” thực sự nghĩa là gì?

Trong quá trình review phát hiện code đang có hai cách hiểu:

```text
Cách A:
status == ACTIVE
```

và:

```text
Cách B:
status == ACTIVE
AND startAt <= now
AND now < expiresAt
```

Ví dụ:

```text
status = ACTIVE
expiresAt = hôm qua
```

Nếu chỉ nhìn enum thì License vẫn ACTIVE.

Nhưng về business nó đã hết hiệu lực.

Điều này có thể khiến:

- một chỗ từ chối issue License mới vì thấy License cũ vẫn ACTIVE,
- một chỗ khác lại coi License đó đã hết hạn.

Vì vậy cần tách:

```text
persisted status
```

khỏi:

```text
effective business validity
```

Rule được chốt:

```text
License usable tại thời điểm now
=
license.status == ACTIVE
AND license.startAt <= now
AND now < license.expiresAt
```

Tên domain dự kiến:

```text
isEffectivelyActiveAt(now)
```

Scheduler không phải thứ quyết định Driver có được phép sử dụng hay không.

Nếu:

```text
status = ACTIVE
expiresAt < now
```

thì License đã unusable ngay cả khi cron chưa đổi persisted status sang `EXPIRED`.

Scheduler/reconciliation vẫn hữu ích để:

- làm sạch persisted state,
- ghi audit event,
- giữ unique constraint và lifecycle data nhất quán theo thời gian.

Nhưng authorization/business protection không được phụ thuộc vào việc scheduler chạy đúng giây.

---

### 2.8 Sau đó phát sinh câu hỏi: booking đã trả tiền thì sao?

Khi đã chốt:

```text
License invalid
→ block business mới
```

thì xuất hiện case:

```text
10:00 Driver book và trả tiền lịch 18:00
14:00 License bị SUSPENDED/EXPIRED
18:00 Driver tới Station
```

Hướng đầu tiên có thể là:

```text
License hiện không hợp lệ
→ không cho Driver bắt đầu
→ cancel booking
→ refund
```

Technically khá nhất quán.

Nhưng business/UX lại rất khó chịu.

Driver đã:

- tìm Station khi nó hợp lệ,
- tạo booking hợp lệ,
- có thể đã trả tiền,
- sắp xếp lịch để tới Station.

Sau đó một vấn đề giữa Platform và Owner xảy ra.

Nếu hệ thống:

```text
auto cancel
→ refund
→ Driver phải tìm Station khác
```

thì Driver đang phải chịu hậu quả cho một vấn đề mà họ không gây ra.

Ngoài UX, backend cũng phình thêm workflow:

```text
License problem
→ cancel booking
→ refund
→ notify Driver
→ xử lý refund failure
→ có thể dispute
```

Với scope FYP hiện tại, complexity này không đáng nếu nguyên nhân chỉ là License entitlement.

---

### 2.9 Quyết định refinement: License mất hiệu lực chỉ chặn BUSINESS MỚI

Từ vấn đề booking đã trả tiền, policy được refine thành:

```text
License loss stops NEW business only.
```

Cụ thể:

```text
Driver search/map sau khi License mất hiệu lực
→ không hiện Station

New booking sau khi License mất hiệu lực
→ reject

Existing confirmed/paid booking
được tạo trước khi License mất hiệu lực
→ preserve, vẫn honor

Charging session đã bắt đầu
→ cho phép hoàn thành
```

Ví dụ:

```text
10:00 Driver booking + pay cho 18:00
14:00 License SUSPENDED
14:01 Station biến mất khỏi discovery
14:01 booking mới bị reject

18:00 Driver có booking cũ
→ vẫn được sử dụng booking đó
```

Lý do:

> Không retroactively phá một cam kết đã được tạo hợp lệ với Driver chỉ vì sau đó Owner gặp vấn đề License.

Đây là một customer-protection decision có chủ đích.

---

### 2.10 Ranh giới quan trọng: License problem khác safety/operational problem

Rule giữ booking cũ **chỉ áp dụng cho License entitlement**.

Không được suy rộng thành:

> Station có vấn đề gì cũng phải honor booking.

Ví dụ tương lai Station bị suspend vì:

- safety,
- hardware nguy hiểm,
- legal prohibition,
- fraud,
- physical operational issue,

thì có thể phải:

```text
block charging
cancel booking
refund
```

kể cả booking đã tồn tại.

Đó là một business policy khác và hiện tại nằm ngoài scope.

Điều này giúp tránh biến License policy thành một rule chung cho mọi loại suspension.

---

### 2.11 Cuối cùng pessimistic locking ban đầu đi đâu?

Pessimistic locking ban đầu được cân nhắc để bảo vệ giả định:

```text
Station ACTIVE
=> License phải ACTIVE ở mọi thời điểm
```

Nhưng sau khi business model thay đổi, rule này bị bỏ.

Bây giờ:

```text
Station ACTIVE + License SUSPENDED
```

là state hợp lệ.

Do đó không còn lý do phải pessimistic-lock chỉ để ép Station và License status luôn đồng bộ.

Concurrency vẫn được xử lý ở những nơi có mục đích rõ:

#### Cùng một License bị nhiều admin sửa

```text
suspend
cancel
reactivate
expire
```

→ dùng optimistic locking qua `License.version`.

Một request thắng, request stale còn lại trả conflict.

Nếu transaction thua thì `LicenseStatusEvent` của nó cũng không được persist.

#### Hai admin cùng issue License

→ DB partial unique index là final guard.

→ map đúng constraint violation thành:

```text
409 ACTIVE_LICENSE_ALREADY_EXISTS
```

#### Booking/payment

Đây vẫn là domain cần concurrency protection mạnh vì có race trực tiếp trên:

- slot/time,
- charger,
- tiền,
- refund/payment state.

Không nên lấy policy concurrency của License rồi áp máy móc sang Booking/Payment hoặc ngược lại.

---

### 2.12 Chuỗi reasoning cuối cùng

Có thể nhớ toàn bộ quyết định theo câu chuyện này:

```text
Phát hiện cross-entity race
        ↓
Ban đầu nghĩ Station và License phải sync status
        ↓
Ý tưởng: License suspend/expire -> Station suspend/inactive
        ↓
Nghe dễ hiểu, dễ code, dễ defend
        ↓
Nhưng bắt đầu xuất hiện câu hỏi:
Owner quên renew thì sao?
Station bị suspend vì lý do khác thì sao?
Renew License có tự mở Station lại không?
        ↓
Grace period được cân nhắc
        ↓
Nhưng grace tạo thêm quá nhiều business rule chưa có requirement
        ↓
Nhận ra Station status và License entitlement là hai khái niệm khác nhau
        ↓
Chấp nhận:
Station ACTIVE + License SUSPENDED
là stored state hợp lệ
        ↓
Không sync status
        ↓
Tính Driver eligibility tại point of use
        ↓
Chấp nhận validation nhiều hơn một chút
nhưng gom thành policy/query chung
        ↓
Phát hiện ACTIVE status thôi chưa đủ
        ↓
Định nghĩa effective License:
ACTIVE + nằm trong time window
        ↓
Lại xuất hiện câu hỏi:
booking đã trả tiền trước khi License hỏng thì sao?
        ↓
Auto-cancel/refund gây UX xấu
và làm workflow phức tạp
        ↓
Chốt:
License mất hiệu lực chỉ chặn BUSINESS MỚI
        ↓
Existing confirmed/paid booking vẫn được honor
Charging đang chạy vẫn hoàn thành
        ↓
Final:
License quyết định quyền nhận business mới,
không trực tiếp thay đổi Station entity
```

---

## 3. Core decision

`Station.status` và `License.status` độc lập với nhau. Hệ thống **không tự động đồng bộ** hai status này.

| Khái niệm | Ý nghĩa |
| --- | --- |
| `Station.status` | Trạng thái approval và operational của chính Station trên platform. |
| `License` | Entitlement/quyền của Owner để Station nhận business mới thông qua ChargeOps. |
| `ChargePoint.provisioningStatus` | Trạng thái onboarding/activation của hardware; không phản chiếu License. |
| `Connector.runtimeStatus` | Tình trạng runtime của connector (`AVAILABLE`, `IN_USE`, `OFFLINE`). |
| Driver eligibility | Policy dẫn xuất từ Station, License, hardware và runtime; không lưu thành một status tổng hợp mới trong DB. |

Do đó state sau là hợp lệ:

```text
Station.status = ACTIVE
License.status = SUSPENDED
```

Station vẫn được approve và vẫn tồn tại trong DB. Owner/Admin vẫn có thể xem và quản lý Station. Station chỉ không được nhận Driver business mới cho tới khi License usable trở lại.

---

## 4. Định nghĩa

### 4.1 Station active

Station active khi:

```text
station.status == ACTIVE
```

Trong model hiện tại, các status khác (`PENDING_APPROVAL`, `REJECTED`, `SUSPENDED`, `WITHDRAWN`) đều không Driver-eligible.

Model hiện không có generic `INACTIVE`; business rule nên hiểu:

```text
station.status != ACTIVE
```

là không đủ điều kiện ở gate này.

### 4.2 Effectively active License

License usable tại thời điểm `now` khi và chỉ khi:

```text
license.status == ACTIVE
AND license.startAt <= now
AND now < license.expiresAt
```

Rule này được đặt tên trong domain model là:

```text
isEffectivelyActiveAt(now)
```

Các License sau không usable cho business mới:

- `PENDING`
- `SUSPENDED`
- `CANCELLED`
- `EXPIRED`
- `ACTIVE` nhưng nằm ngoài effective time window

Một License vẫn lưu `ACTIVE` nhưng `expiresAt` đã qua phải được coi là unusable ngay cả khi scheduler chưa persist `EXPIRED`.

### 4.3 Equipment eligibility

ChargePoint và Connector có hai loại state khác nhau:

```text
ChargePoint.provisioningStatus
  = trạng thái hardware đã được platform provision/activate hay chưa

Connector.runtimeStatus
  = connector hiện AVAILABLE, IN_USE hay OFFLINE
```

License mất hiệu lực không được tự động đổi hai state này. Ví dụ sau là hợp lệ:

```text
Station ACTIVE
+ License SUSPENDED
+ ChargePoint ACTIVE
+ Connector AVAILABLE
```

Hardware vẫn được provision và có thể quản lý, nhưng Station không được nhận
business mới vì License không usable.

`qrToken` tồn tại hay QR image đã được render không phải readiness gate.
Token là định danh check-in; readiness phụ thuộc state và policy.

### 4.4 Driver eligibility

Station được xuất hiện trong discovery khi:

```text
stationDiscoverable(station, now)
  = station.status == ACTIVE
  AND station có một License effectively active tại now
  AND tồn tại ChargePoint provisioningStatus == ACTIVE
  AND tồn tại Connector runtimeStatus == AVAILABLE
```

Khi tạo booking, backend phải kiểm tra lại trên Connector cụ thể:

```text
connectorBookable(connector, now)
  = station.status == ACTIVE
  AND License effectively active
  AND chargePoint.provisioningStatus == ACTIVE
  AND connector.runtimeStatus == AVAILABLE
  AND các gate operating-hours, pricing và slot đều đạt
```

Discovery và `createBooking()` phải dùng cùng core eligibility rule; booking
có thêm các gate trên connector/time slot cụ thể.

Ẩn Station trên UI chỉ là UX. `createBooking()` phía backend mới là
business-protection boundary.

---

## 5. Decision table

| Station status | License usability | Equipment ready | Driver search/map | New booking | Owner/Admin management |
| --- | --- | --- | --- | --- | --- |
| `ACTIVE` | Usable | Có CP `ACTIVE` và Connector `AVAILABLE` | Hiện | Cho phép nếu các gate time/price/slot đạt | Cho phép |
| `ACTIVE` | Usable | Không ready | Ẩn | Từ chối | Cho phép |
| `ACTIVE` | Không usable | Bất kỳ | Ẩn | Từ chối | Cho phép |
| Không phải `ACTIVE` | Bất kỳ | Bất kỳ | Ẩn | Từ chối | Cho phép tùy authorization/policy |

Operating hours, pricing và booking-slot availability là các gate bổ sung cho
booking. Chúng không thay thế Station, License hoặc equipment eligibility.

---

## 6. Station approval và License thay đổi về sau

### 6.1 Ranh giới với Admin License lifecycle policy

Tài liệu này định nghĩa Driver/business eligibility sau khi trạng thái License
đã thay đổi. Nó không phải nguồn sự thật cho việc Admin có được phép thực hiện
`suspend`, `reactivate`, `cancel` hoặc `renew` hay không.

Policy của command Admin được định nghĩa tại
[Admin License Lifecycle Policy](./license-admin-backend-api-design.md#7-admin-license-lifecycle-policy).

Hai policy nối với nhau theo hướng một chiều:

```text
Admin lifecycle command
-> License state/effective window thay đổi
-> discovery và createBooking tính lại eligibility tại point of use
```

Eligibility policy không gọi ngược lifecycle command và không tự mutate
License, Station, ChargePoint hoặc Connector.

Approve Station có nghĩa:

```text
Tại thời điểm approve:
Station đang PENDING_APPROVAL
và có License effectively active.
```

Nó **không** có nghĩa License phải ACTIVE mãi mãi sau đó.

Nếu License sau này:

- `SUSPENDED`,
- `CANCELLED`,
- hoặc hết hạn,

thì:

- Station giữ nguyên status riêng, thông thường vẫn `ACTIVE`.
- Station không còn eligible cho Driver discovery và booking mới.
- Không cần mutate Station status chỉ vì License thay đổi.
- Không cần cross-entity event hoặc scheduled synchronization chỉ để giữ hai status giống nhau.

Combination sau được cố ý cho phép:

```text
Station ACTIVE + License SUSPENDED
```

---

## 7. Existing booking và charging session

License mất usability chỉ chặn **business mới**.

| Tình huống khi License bị suspended/cancelled/expired | Quyết định của project |
| --- | --- |
| Driver search/map sau thời điểm đó | Không hiện Station |
| Booking mới sau thời điểm đó | Reject |
| Booking confirmed/paid đã tạo trước đó | Giữ nguyên; không auto-cancel/auto-refund |
| Charging session đang chạy | Cho phép hoàn thành |

Lý do là không bắt Driver đã trả tiền phải chịu hậu quả từ vấn đề License giữa Platform và Station Owner.

> Exception này chỉ áp dụng cho **License**. Một policy tương lai về Station suspension do safety/legal/physical-operational issue có thể cần cancel booking hoặc block charging.

---

## 8. Concurrency policy

### 8.1 Station approval và License change

Không cần pessimistic lock chỉ để đồng bộ Station và License.

State:

```text
Station ACTIVE + License SUSPENDED
```

là hợp lệ.

Driver-facing search và new-booking check sẽ quyết định eligibility tại point of use.

### 8.2 Nhiều request thay đổi cùng một License

Các operation trên cùng một License:

```text
suspend
cancel
reactivate
expire
```

dùng optimistic locking thông qua:

```text
License.version
```

Nếu hai admin thao tác trên stale copy:

1. Một update thành công và ghi status event.
2. Update còn lại fail với conflict response.
3. Transaction fail không được persist `LicenseStatusEvent`.

Điều này bảo vệ terminal state như `CANCELLED` khỏi bị một request `SUSPENDED` cũ overwrite.

Pessimistic locking không phải default solution.

### 8.3 Concurrent License issue

Một Station chỉ được có tối đa một `ACTIVE` License.

DB partial unique index là final guard nếu hai admin issue cùng lúc.

Expected API behaviour:

```text
Winner:
License được issue và activate.

Loser:
HTTP 409 / ACTIVE_LICENSE_ALREADY_EXISTS.
```

Implementation chỉ được translate **đúng unique-constraint violation liên quan** thành business error này. Không được biến mọi `DataIntegrityViolationException` thành duplicate-license error.

---

## 9. Persistence và lifecycle rules

### 9.1 Optimistic lock column

`@Version` cần column vật lý:

```text
version bigint NOT NULL DEFAULT 0
```

trong table `licenses`.

Nếu migration thêm column đã được apply trong shared environment thì phải tạo Flyway migration mới; không sửa migration đã chạy vì Flyway kiểm tra checksum.

### 9.2 Expiry reconciliation

Authorization luôn sử dụng effective-active rule ở section 4.2.

Do đó scheduler **không phải requirement để bảo vệ Driver**.

Scheduler hoặc reconciliation trong tương lai vẫn nên eventually persist:

```text
ACTIVE/SUSPENDED/PENDING → EXPIRED
```

và ghi:

```text
LicenseStatusEvent
actor = SYSTEM
```

Mục đích:

- persisted state phản ánh thời gian,
- audit history đầy đủ,
- unique constraint và lifecycle data được reconcile.

### 9.3 License status events

Mỗi License state transition tạo append-only audit data, bao gồm:

- transition,
- actor,
- time,
- optional reason.

`RENEW` không phải transition trên License cũ.

Renew tạo **License row mới**, bắt đầu lifecycle riêng với event:

```text
ISSUED
```

---

## 10. Implementation contract

Khi các API tương ứng được implement, các behaviour sau là bắt buộc:

1. Driver station search/map filter theo Station `ACTIVE`, effective License,
   ChargePoint `ACTIVE` và ít nhất một Connector `AVAILABLE`.
2. `createBooking()` server-side re-check cùng core eligibility rule trên
   Connector cụ thể trước khi tạo booking.
3. Owner/Admin dashboard vẫn có thể hiện Station và hardware dù License không
   usable, tùy authorization bình thường.
4. Station approval chỉ yêu cầu effective License hợp lệ **tại thời điểm approve**.
5. Hardware activation không được dùng để cache hoặc thay thế License eligibility.
6. Command thay đổi cùng một License dùng optimistic locking và trả conflict cho stale update.
7. License expiry không tự động thay đổi Station, ChargePoint hoặc Connector status.

---

## 11. Explicit non-goals

Các vấn đề sau cố ý nằm ngoài scope FYP hiện tại:

- Grace period sau khi License hết hạn.
- Auto-cancel/refund chỉ vì License thay đổi.
- Purchase/payment License fee ngay trong platform.
- Tự động synchronization Station status và License status.
- Pessimistic locking giữa Station approval và License update.
- Policy xử lý booking/session khi Station bị suspend vì safety/operational reason.

---

## 12. Quy tắc một câu

> **License quyết định một Station vốn đang ACTIVE có được phép nhận business mới thông qua ChargeOps hay không; License không trực tiếp thay đổi bản thân Station và cũng không hồi tố làm mất hiệu lực những booking đã được confirm trước khi License trở nên unusable.**
