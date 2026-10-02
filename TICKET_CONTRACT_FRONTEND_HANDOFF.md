# Ticket contract handoff — Owner, Admin, Driver

## Ranh giới người xử lý

`assignedHandlerId`, `assignedHandlerName`, `assignedHandlerKind` là **một người xử lý vận hành hiện hành**. Với ticket trạm, người này là Owner hoặc Staff đúng trạm; với ticket nền tảng, người này là Admin. `null` nghĩa là chưa phân công. `IN_PROGRESS` không đủ để tự suy ra tên, vai trò hoặc sự tồn tại của handler. `reporterId` là người báo sự cố; `escalation.requestedBy` là người yêu cầu phân xử; Admin xem case trạm không trở thành handler của trạm.

Owner có thể claim/assign cho Owner hoặc Staff đúng trạm qua `/owner/tickets/{id}/claim` và `/owner/tickets/{id}/assignment`. Yêu cầu Admin xem tranh chấp là hành động khác: `POST /tickets/{id}/escalation`. Admin chỉ claim/assign ticket **nền tảng** qua `/admin/tickets/{id}/claim` và `/admin/tickets/{id}/assignment`; trên case trạm đã escalate, Admin chỉ đọc, trao đổi và theo dõi bằng chứng theo quyền hiện có. Không hiện nút điều hướng nhân viên trạm; không gọi `/admin/tickets/{id}/station-handlers` vì route đó đã bị bỏ.

## Response và những điểm frontend phải cập nhật

- Các endpoint GET chi tiết `/tickets/{id}`, `/owner/tickets/{id}`, `/admin/tickets/{id}` trả `TicketDetailResponse` dạng nhóm: `overview`, `station`, `booking`, `participants`, `conversation`, `resolution`, `isEscalated`, `escalation`, `escalationAvailability`.
- Các endpoint danh sách và mutation hiện trả `TicketResponse` dạng phẳng. Frontend cần phân biệt loại response theo endpoint. Sau mutation, tải lại GET chi tiết để lấy trạng thái mới thay vì trộn response phẳng vào cache detail.
- Không dùng `isInProgress` hoặc tên fallback để kết luận đã có handler. Chỉ `participants.assignedHandlerId != null` là có handler trong detail; danh sách dùng `assignedHandlerId`.
- `escalationAvailability.canRequest` là quyết định của server cho nút chuyển Admin. `WAITING_FOR_STATION` đi kèm `availableAt` cho đồng hồ 24 giờ. `escalation != null` chỉ có nghĩa yêu cầu đã được gửi, chưa khẳng định Admin đang phân xử.
- `GET /tickets/{id}/escalation` trả `200` với `data: null` nếu chưa chuyển case. HTTP client phải giữ `null`, không biến cả envelope thành một escalation object. Frontend mới nên dùng dữ liệu escalation trong GET detail, không polling endpoint này riêng.
- Backend `TicketEscalationResponse` hiện chỉ có `requestedBy` UUID, không có `requestedByRole`; UI không được mặc định hiển thị "Chủ trạm" khi field này vắng. Cần đối chiếu reporter/owner theo dữ liệu được phép hoặc chờ backend bổ sung role tường minh.
- `refundIds` biểu thị các nghĩa vụ hoàn liên quan, **không** chứng minh hoàn đã thành công. Không hiển thị "Hoàn 100%" chỉ dựa vào mảng này; trạng thái `PENDING` và `SUCCEEDED` thuộc refund API.
- `GET /owner/tickets/{id}/messages` nay có trên backend và dùng cùng quyền sở hữu ticket của Owner. Admin có `/admin/tickets/{id}/messages`. Có thể dùng `conversation.messages` trong detail cho lần tải đầu và endpoint messages cho polling chat.

## Driver xác nhận kết quả

Backend đã có `PATCH /api/v1/tickets/{ticketId}/status` cho reporter xác nhận `RESOLVED → CLOSED`, body `{ "expectedVersion": <version>, "status": "CLOSED" }`. Hệ thống ghi `closeReason = REPORTER_CONFIRMED`. Reporter cũng có thể báo vấn đề còn tồn tại bằng `RESOLVED → IN_PROGRESS` với lý do. Owner/Admin không được xác nhận thay reporter. Scheduler đóng khi quá hạn 10 ngày với `AUTO_CLOSED_NO_RESPONSE`; UI phải phân biệt với xác nhận chủ động.

## Các sửa frontend cụ thể

1. `TicketHandlerCard`: với Admin trên ticket trạm, chỉ đọc handler; bỏ nút điều hướng ở cả trạng thái đã gán và chưa gán.
2. `AssignTicketDrawer`: không fetch `station-handlers` ở nhánh Admin trạm; chỉ dùng cho Owner trạm và Admin nền tảng theo quyền tương ứng.
3. `useTicketDetail`: lấy escalation từ `ticketQuery.data.escalation`, dùng `escalationAvailability`, không gọi riêng GET escalation khi mở ticket; bỏ suy luận handler từ `IN_PROGRESS`.
4. `TicketList`: bỏ nhãn "Hoàn 100%" dựa trên `refundIds`; nếu cần badge escalation ở danh sách, backend cần bổ sung summary field hoặc frontend dùng danh sách `/admin/tickets/escalated` để biết scope.
5. Driver Mobile: hiển thị hai hành động khi `RESOLVED`: xác nhận đóng và báo vấn đề còn; gửi đúng `expectedVersion` và cập nhật ticket sau kết quả.
