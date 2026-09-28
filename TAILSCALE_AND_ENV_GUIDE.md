# ChargeOps — NGINX Development Gateway & Tailscale Funnel

## Mục tiêu

Trình duyệt và mobile chỉ biết một public origin:

```text
https://thang.tail704409.ts.net
```

Không service nào tự đăng ký path với Funnel. Funnel chỉ chuyển toàn bộ HTTPS
443 vào NGINX gateway; NGINX là nơi duy nhất quyết định request đi đâu.

```text
Internet / Mobile
        │
        ▼
Tailscale Funnel :443
        │
        ▼
127.0.0.1:8088 → chargeops-gateway (NGINX)
        ├── /             → Web Portal / Vite :5173
        ├── /api/         → Spring Boot :8081
        ├── /realms/      → Keycloak :8080
        ├── /resources/   → Keycloak :8080
        ├── /.well-known/ → Keycloak :8080
        └── /grafana/     → Grafana :3000
```

Prometheus, Loki và Alloy chỉ chạy trong Docker network. Trình duyệt xem dữ
liệu của chúng qua Grafana, không truy cập trực tiếp.

## Development workflow

1. Chạy Web Portal bằng Vite trên port `5173`.
2. Chạy Spring Boot trên port `8081`.
3. Chạy `start-funnel.bat` tại repository frontend.

Script sẽ:

1. Khởi động `chargeops-gateway` và các dependency Docker.
2. Kiểm tra `http://127.0.0.1:8088/healthz`.
3. Xóa cấu hình Funnel nhiều path/cổng cũ bằng `tailscale funnel reset`.
4. Tạo đúng một Funnel `443 → http://127.0.0.1:8088`.

Local gateway cũng dùng được mà không cần Tailscale:

```text
http://localhost:8088
http://localhost:8088/api/v1/stations
http://localhost:8088/realms/chargeops/.well-known/openid-configuration
http://localhost:8088/grafana/
```

## Public URLs

| Chức năng | URL |
|---|---|
| Web Portal | `https://thang.tail704409.ts.net` |
| Backend API | `https://thang.tail704409.ts.net/api/v1` |
| Keycloak issuer | `https://thang.tail704409.ts.net/realms/chargeops` |
| Grafana | `https://thang.tail704409.ts.net/grafana/` |

Không dùng port `8443` nữa.

## Driver mobile app

Mobile native không cần một Funnel riêng. App chỉ là client gọi API/OIDC qua
cùng gateway:

```dotenv
EXPO_PUBLIC_KEYCLOAK_ISSUER_URL=https://thang.tail704409.ts.net/realms/chargeops
EXPO_PUBLIC_KEYCLOAK_ACCOUNT_URL=https://thang.tail704409.ts.net/realms/chargeops/account
EXPO_PUBLIC_API_BASE_URL=https://thang.tail704409.ts.net
EXPO_PUBLIC_OWNER_PORTAL_URL=https://thang.tail704409.ts.net
```

Các service mobile tự thêm `/api/v1` vào base URL. Keycloak trả kết quả đăng
nhập về deep link native, ví dụ `chargeops://auth/callback`.

Port `8082` chỉ phục vụ Expo Metro/Expo Web trong quá trình phát triển:

- Expo Go có thể tải bundle qua LAN, QR hoặc Expo tunnel.
- API và Keycloak vẫn đi qua public gateway ở trên.
- Bản APK/IPA production không phụ thuộc Metro port `8082`.

Nếu cần public hóa bản **Expo Web** của driver, phải cấu hình một base path riêng
như `/driver-app/`; không đặt nó ở `/` vì `/` đã dành cho Web Portal.

## Keycloak URLs cần cập nhật

Grafana client `chargeops-observability`:

```text
Valid redirect URI:
https://thang.tail704409.ts.net/grafana/login/generic_oauth

Web origin:
https://thang.tail704409.ts.net
```

Web Portal client `chargeops-web`:

```text
Valid redirect URI:
https://thang.tail704409.ts.net/*

Web origin:
https://thang.tail704409.ts.net
```

Mobile client giữ redirect URI native chính xác theo app, ví dụ:

```text
chargeops://auth/callback
```

## Troubleshooting

```powershell
# Gateway local
curl.exe http://127.0.0.1:8088/healthz

# Xem trạng thái container
docker compose ps

# Kiểm tra NGINX
docker compose exec gateway nginx -t

# Xem cấu hình Funnel duy nhất
tailscale funnel status
```

Kết quả Funnel mong đợi:

```text
https://thang.tail704409.ts.net (Funnel on)
|-- / proxy http://127.0.0.1:8088
```

Nếu `/` trả `502`, kiểm tra Vite port `5173`. Nếu `/api` trả `502`, kiểm tra
Spring Boot port `8081`. Nếu `/realms` hoặc `/grafana` trả `502`, kiểm tra
container Keycloak/Grafana.
