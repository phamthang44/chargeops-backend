# ChargeOps Account Console

Account Console tùy biến cho Keycloak `26.0.8`. Giao diện chỉ hiển thị hai nhóm bảo mật mà mobile app cần:

- đổi mật khẩu;
- bật, thêm hoặc gỡ ứng dụng xác thực TOTP (2FA).
- theo dõi thiết bị/phiên đăng nhập, IP, thời gian truy cập và đăng xuất từng phiên khác.

Username và email không có form chỉnh sửa. Mọi thao tác credential đều gọi Application Initiated Actions chính thức của Keycloak, nên việc xác thực lại, cập nhật password và TOTP vẫn do Keycloak xử lý.

JAR cung cấp hai theme:

- `chargeops-account`: Account Console React;
- `chargeops-security`: login theme con kế thừa `chargeops-driver`, chỉ override màn đổi mật khẩu, thiết lập TOTP và message bảo mật.

## Chạy cùng ChargeOps

Từ thư mục backend:

```bash
docker compose up -d --build keycloak
```

Docker image sẽ tự build React UI, đóng gói theme JAR và chép JAR vào `/opt/keycloak/providers`.

Sau lần triển khai đầu tiên, realm `chargeops` cần đặt:

- Account theme: `chargeops-account`;
- Login theme: `chargeops-security`;
- Email theme: `chargeops-driver`.

## Phát triển giao diện

```bash
npm install
npm run dev
```

Kiểm tra production build:

```bash
npm run build
mvn resources:resources jar:jar
```

JAR được tạo tại `target/chargeops-account-console-1.0.0.jar`.
