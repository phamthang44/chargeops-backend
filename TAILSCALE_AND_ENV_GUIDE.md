# Hướng Dẫn Cấu Hình Mạng, Môi Trường (.env) & Vận Hành Tailscale Funnel

> **Tài liệu lưu trữ tại**: `chargeops/TAILSCALE_AND_ENV_GUIDE.md`  
> **Mục đích**: Tổng hợp toàn bộ kiến trúc kết nối từ iPhone/Internet vào máy phát triển cục bộ, chi tiết cấu hình các file `.env`, lý do kỹ thuật đằng sau mỗi thiết lập và hướng dẫn các lệnh bật Tailscale chuẩn xác để không bị lỗi 404/502/Timeout.

---

## 1. Sơ Đồ Kiến Trúc Kết Nối

```
[ iPhone / Thiết bị bên ngoài ]
               │
               ▼  (HTTPS Public Domain qua Tailscale Funnel)
    https://thang.tail704409.ts.net
               │
       ┌───────┴────────────────────────┬────────────────────────┐
       ▼ (:443 /)                       ▼ (:443 /api)            ▼ (:8443 /)
Keycloak Identity Service      Spring Boot Backend API     Owner & Operator Web Portal
(Local Docker Port 8080)       (Local App Port 8081)       (Local Vite Port 5173)
```

---

## 2. Chi Tiết Các File `.env` & Lý Do Phải Cấu Hình

### A. Mobile Driver App (`chargeops-driver-mobile/.env`)
```env
EXPO_PUBLIC_KEYCLOAK_ISSUER_URL=https://thang.tail704409.ts.net/realms/chargeops
EXPO_PUBLIC_KEYCLOAK_DRIVER_CLIENT_ID=chargeops-driver-mobile
EXPO_PUBLIC_KEYCLOAK_REDIRECT_SCHEME=chargeops
EXPO_PUBLIC_OWNER_PORTAL_URL=https://thang.tail704409.ts.net:8443
EXPO_PUBLIC_API_BASE_URL=https://thang.tail704409.ts.net
```
* **Lý do**:
  1. Khi người dùng bấm **Google Login** trên điện thoại, Google OAuth **từ chối hoàn toàn** các địa chỉ IP nội bộ (`192.168.x.x` hoặc `100.x.x.x`) và yêu cầu bắt buộc phải là tên miền HTTPS hợp lệ.
  2. Do đó, `KEYCLOAK_ISSUER_URL` phải dùng domain HTTPS `thang.tail704409.ts.net`.
  3. `EXPO_PUBLIC_API_BASE_URL` trỏ vào domain `https://thang.tail704409.ts.net` để các API request (`/api/v1/me/profile`, `/api/v1/stations`, v.v.) được gửi thông suốt qua Funnel tới Backend mà không cần cài app Tailscale trên iPhone.

---

### B. Backend Resource Server (`chargeops-backend/chargeops/.env`)
```env
KEYCLOAK_ISSUER_URI=http://localhost:8080/realms/chargeops
KEYCLOAK_ADMIN_BASE_URL=http://localhost:8080
```
* **Lý do cực kỳ quan trọng (Tránh lỗi Read timed out)**:
  - Backend Spring Boot chạy **ngay trên máy tính của bạn**, cùng máy với Docker container Keycloak (port 8080).
  - Nếu cấu hình backend gọi Keycloak qua domain ngoài `https://thang.tail704409.ts.net`, mỗi request xác thực token / khởi tạo `jwtDecoder` của Spring Boot sẽ phải chạy vòng ra mạng internet qua Tailscale relay rồi mới quay trở lại máy tính ➔ Gây độ trễ lớn và sinh ra lỗi nghiêm trọng:
    ```
    SocketTimeoutException: Read timed out on GET https://thang.tail704409.ts.net/realms/chargeops/.well-known/openid-configuration
    ```
  - Thiết lập `http://localhost:8080` giúp backend kết nối trực tiếp với Keycloak cục bộ với **độ trễ 0ms**.
* **Đồng bộ mã nguồn (`SecurityConfig.java`)**:
  - Khi người dùng đăng nhập từ bên ngoài, Access Token JWT do Keycloak sinh ra sẽ có claim `iss: "https://thang.tail704409.ts.net/realms/chargeops"`.
  - Chúng tôi đã cập nhật `SecurityConfig.java` để:
    - Tải chứng chỉ công khai (JWKs) nhanh chóng từ mạng nội bộ `http://localhost:8080/.../certs`.
    - Trình xác thực (`JwtClaimValidator`) chấp nhận linh hoạt claim `iss` chứa `/realms/chargeops` (chấp nhận cả localhost lẫn domain Tailscale).

---

### C. Web Portal (`chargeops-web/apps/web/.env`)
```env
VITE_KEYCLOAK_ENABLED=true
VITE_KEYCLOAK_URL=https://thang.tail704409.ts.net
VITE_KEYCLOAK_REALM=chargeops
VITE_KEYCLOAK_CLIENT_ID=chargeops-web

VITE_USE_MOCKS=false
VITE_API_URL=https://thang.tail704409.ts.net/api/v1

VITE_DRIVER_APP_URL_DEV=http://localhost:8082
VITE_DRIVER_APP_URL=http://localhost:8082
```
* **Lý do**:
  - Cho phép người quản trị/chủ trạm truy cập Web Portal từ ngoài qua cổng 8443 (`https://thang.tail704409.ts.net:8443`), đăng nhập bằng Keycloak và gọi API backend bình thường.

---

## 3. ⚠️ 2 Bẫy Cốt Lõi Khi Setup Tailscale Funnel Cần Chú Ý

### ⚠️ Bẫy 1: Lỗi Cắt Bỏ Tiền Tố Path (`Path Stripping`) Gây 404
* **Hiện tượng**: App mobile báo lỗi `404 Not Found` khi gọi `/api/v1/me/profile`.
* **Nguyên nhân**:
  - Khi chạy lệnh: `tailscale funnel --set-path /api http://127.0.0.1:8081` (đích không có `/api`), cơ chế mặc định của Tailscale là **cắt bỏ chữ `/api`** trước khi forward request vào Spring Boot.
  - Hậu quả: Request `https://.../api/v1/me/profile` khi đến Spring Boot bị biến thành `GET /v1/me/profile`. Trong khi đó Spring Boot chỉ đăng ký controller ở `/api/v1/me/profile` ➔ Bị 404!
* **✅ Cách thiết lập đúng**:
  ```powershell
  tailscale funnel --bg --set-path /api http://127.0.0.1:8081/api
  ```
  *(Phải ghi rõ đuôi `http://127.0.0.1:8081/api` để Tailscale giữ nguyên tiền tố `/api`)*

---

### ⚠️ Bẫy 2: Lỗi 502 Bad Gateway Trên Web Portal (Cổng 8443)
* **Hiện tượng**: Truy cập `https://thang.tail704409.ts.net:8443` báo `502 Bad Gateway`.
* **Nguyên nhân**:
  - Vite dev server trên Windows mặc định lắng nghe trên địa chỉ IPv6 `[::1]:5173` (`localhost`), chứ không bind vào IPv4 `127.0.0.1:5173`.
  - Nếu trỏ proxy vào `http://127.0.0.1:5173`, kết nối bị từ chối và Tailscale trả về 502.
* **✅ Cách thiết lập đúng**:
  ```powershell
  tailscale funnel --bg --https=8443 http://localhost:5173
  ```
  *(Dùng chữ `localhost` thay vì `127.0.0.1`)*

---

## 4. Script 1-Click Kích Hoạt Tailscale Funnel (Mỗi Khi Mở Máy)

Mỗi lần khởi động lại máy tính hoặc khi cần test app trên iPhone, bạn chỉ cần mở PowerShell (quyền Admin hoặc User thường đều được) và chạy 3 lệnh sau:

```powershell
# 1. Expose Keycloak (Cổng 8080 -> https://thang.tail704409.ts.net:443/)
tailscale funnel --bg http://127.0.0.1:8080

# 2. Expose Spring Boot API (Cổng 8081 -> https://thang.tail704409.ts.net:443/api)
tailscale funnel --bg --set-path /api http://127.0.0.1:8081/api

# 3. Expose Web Portal (Cổng 5173 -> https://thang.tail704409.ts.net:8443/)
tailscale funnel --bg --https=8443 http://localhost:5173
```

Hoặc chạy file `start-funnel.bat` tại thư mục gốc của frontend.

### Lệnh kiểm tra lại trạng thái:
```powershell
tailscale funnel status
```
Kết quả hiển thị chuẩn xác sẽ như sau:
```
# Funnel on:
#     - https://thang.tail704409.ts.net
#     - https://thang.tail704409.ts.net:8443

https://thang.tail704409.ts.net (Funnel on)
|-- /    proxy http://127.0.0.1:8080
|-- /api proxy http://127.0.0.1:8081/api

https://thang.tail704409.ts.net:8443 (Funnel on)
|-- / proxy http://localhost:5173
```

---

## 5. Danh Sách Kiểm Tra Nhanh (Checklist) Khi Bật Máy Mới

1. [ ] Bật Docker Desktop (chạy Keycloak, Postgres, Redis).
2. [ ] Khởi động Backend Spring Boot (`mvn spring-boot:run` hoặc Run trong IDE ở port 8081).
3. [ ] Khởi động Web Portal (`npm run dev` ở port 5173).
4. [ ] Khởi động Mobile Driver (`npm start` ở port 8082).
5. [ ] Chạy 3 lệnh `tailscale funnel` ở mục 4 (hoặc click đúp file `start-funnel.bat`).
6. [ ] Mở Expo Go trên iPhone, quét mã QR và test mượt mà không cần bất kỳ thao tác cấu hình nào khác!
