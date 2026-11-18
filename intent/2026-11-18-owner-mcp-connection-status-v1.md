---
status: draft
---
# Owner MCP connection confirmation and tool catalog V1

**Problem** — Trang AI Agents hiện hướng dẫn lệnh kết nối Codex và Claude Code, nhưng Owner không thấy ChargeOps đã nhận MCP handshake thành công hay chưa. Quyền hiện chỉ được mô tả khái quát, chưa có danh mục chính xác các tool MCP đang cung cấp.

**Outcome** — Trong trang AI Agents:

- Hiển thị trạng thái chưa có kết nối được xác nhận hoặc lần MCP initialize thành công gần nhất, kèm thời gian.
- Chỉ ghi nhận thành công sau khi backend nhận yêu cầu MCP initialize đã xác thực và trả kết quả thành công; đăng nhập OAuth riêng lẻ không được tính.
- Ghi nhận theo Owner đã xác thực, không đọc hay hiển thị token, prompt, hoặc trạng thái lưu trên máy chạy Codex/Claude.
- Gọi trạng thái từ console chỉ trả về dữ liệu của Owner hiện đăng nhập; trạng thái của Owner khác không thể truy cập.
- Hiển thị catalog ba tool hiện có với chức năng, dữ liệu trả về và giới hạn: `get_owner_operations_summary`, `list_owner_stations`, `list_owner_equipment_attention`.
- Nêu rõ quyền chỉ đọc, dữ liệu thuộc Owner hiện đăng nhập, giới hạn số kết quả, dữ liệu không cung cấp, và trạng thái thiết bị là dữ liệu đã lưu chứ không phải heartbeat trực tiếp.

**Constraints** — Giữ nguyên luồng OAuth, audience, scope `chargeops:mcp:read`, kiểm tra role `OWNER`, rate limit và các quyền hiện có. Backend dùng MCP HTTP `STATELESS`; vì vậy chỉ lưu mốc thời gian initialize thành công gần nhất làm bằng chứng đã kết nối, không tuyên bố có phiên MCP đang hoạt động. Chỉ ghi nhận định danh Owner đã xác thực và thời gian; không lưu bearer token, payload JSON-RPC, prompt, IP, dữ liệu trạm hay nội dung tool response. Catalog phải khớp với tool được backend đăng ký. Không thêm nhà cung cấp AI, quyền ghi, công cụ điều khiển trạm hay thay đổi Keycloak.

**Out of scope** — Theo dõi online liên tục hoặc nhiều thiết bị/client riêng biệt; phân biệt Codex với Claude chỉ dựa trên OAuth client ID dùng chung; lưu lịch sử initialize; gọi tool tự động; thay đổi danh sách tool hoặc chính sách truy cập MCP.

**Open questions** —
