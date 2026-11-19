# SilicoCare API — Xác thực & Người dùng (giai đoạn 1)

Base URL dev: `http://localhost:8080` (đổi bằng `SERVER_PORT` trong `.env`) · Swagger UI: `/swagger-ui.html` · OpenAPI JSON: `/v3/api-docs`

Tài liệu liên quan: [api-predictions.md](api-predictions.md) — lượt chẩn đoán (`/api/predictions`). Tài khoản `USER` chính là hồ sơ bệnh nhân (không còn `/api/patients`).

Mọi request/response dùng JSON UTF-8. Endpoint cần đăng nhập gửi header:

```
Authorization: Bearer <accessToken>
```

## 1. Chạy local

```bash
cp .env.example .env          # điền DB_PASSWORD, JWT_SECRET (>= 32 byte), ADMIN_EMAIL, ADMIN_PASSWORD
docker compose up -d          # Postgres 17 + Mailpit (xem thư OTP tại http://localhost:8025)
./mvnw spring-boot:run
./mvnw clean test             # cần Docker (Testcontainers)
```

Schema do Flyway quản lý (`src/main/resources/db/migration`); Hibernate chỉ `validate`.
Khi khởi động, nếu có `ADMIN_EMAIL` + `ADMIN_PASSWORD` mà email chưa tồn tại thì tạo một tài khoản ADMIN.

## 2. Vai trò

`USER` (bệnh nhân) · `ADMIN` — **viết hoa**, đúng như trong JSON và trong claim `role` của JWT.
(Web đổi sang `patient`/`admin` chữ thường, chỉ trong `src/services/userMapper.ts`.)

- Tự đăng ký luôn tạo `USER`, kèm mã bệnh nhân `BN-000001` (`code`).
- ADMIN tạo tài khoản qua `POST /api/users` cũng luôn là `USER`. Không có API đổi vai trò; tài khoản ADMIN duy nhất được seed từ `ADMIN_EMAIL`/`ADMIN_PASSWORD`.

## 3. Token

| | Access token | Refresh token |
|---|---|---|
| Dạng | JWT HS256 (`iss=silicocare`, `sub`=userId, `role`, `jti`) | Chuỗi ngẫu nhiên 256 bit (base64url) |
| Hạn | 15 phút (`expiresIn` giây) | 7 ngày (`refreshExpiresIn` giây) |
| Lưu ở server | Không | Chỉ lưu SHA-256 → thu hồi được |

- `/refresh` **xoay vòng**: mỗi lần gọi trả cặp token mới, refresh token cũ bị thu hồi.
- Dùng lại một refresh token đã bị thu hồi → coi như bị lộ, **thu hồi mọi phiên** của user đó.
- Đổi mật khẩu, đặt lại mật khẩu, khoá hoặc xoá tài khoản → thu hồi mọi refresh token.
  Access token đang dùng vẫn hợp lệ tới khi hết hạn (tối đa 15 phút).

`AuthResponse`:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "u1nkLTx...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 604800,
  "user": { ...UserResponse }
}
```

`UserResponse` (tài khoản kiêm hồ sơ bệnh nhân):

```json
{
  "id": "5bb190d9-06cc-4427-a8de-339b40c8964d",
  "code": "BN-000012",
  "email": "nguoidung@example.com",
  "phone": "0912345678",
  "fullName": "Nguyễn Văn A",
  "role": "USER",
  "enabled": true,
  "birthDate": "1980-05-20",
  "gender": "MALE",
  "tieuSu": { "cviec": "Thợ nghiền đá", "pxuong": "Nghiền sàng", "tuoinghe": 12, "hutthuoc": "Khong hut thuoc" },
  "createdAt": "2026-10-07T16:57:43.916Z",
  "updatedAt": "2026-10-07T16:57:43.916Z"
}
```

- `gender`: `MALE` | `FEMALE` | `OTHER` | `null`.
- `code`: mã bệnh nhân, không đổi, không dùng lại; `null` với `ADMIN`.
- `tieuSu`: tiểu sử bệnh nhân, dùng **đúng mã cột và mã giá trị của form lâm sàng** để web/app điền sẵn vào form chẩn đoán. `{}` nếu chưa điền. Chỉ nhận các cột sau:

  | Nhóm | Cột | Kiểu |
  |---|---|---|
  | Nghề nghiệp & phơi nhiễm | `cviec`, `pxuong`, `cviec1`, `cviec2` | chữ, ≤ 500 ký tự |
  | | `tuoinghe`, `nampx`, `tgian1`, `tgian2` | số năm, 0–70 |
  | | `cv5nam` | `Co` \| `Khong` |
  | Hút thuốc & tiền sử | `hutthuoc` | `Co hut thuoc` \| `Khong hut thuoc` |
  | | `slthuoc` | số, 0–1000 |
  | | `tiensuhh` | `Co` \| `Khong` |
  | | `benhhh`, `tsnoi`, `tsngoai` | chữ, ≤ 500 ký tự |

  Số chấp nhận dấu phẩy thập phân (`"12,5"`) và được lưu dạng số; giá trị rỗng bị bỏ. Lỗi trả theo khoá `tieuSu.<cột>`.
- Giới tính và năm sinh không nằm trong `tieuSu` mà lấy từ `gender` / `birthDate` (xem quy tắc chép vào `lamSang` ở [api-predictions.md](api-predictions.md)).

## 4. Quy tắc dữ liệu

| Trường | Quy tắc |
|---|---|
| `email` | Bắt buộc, đúng định dạng, ≤ 255 ký tự; lưu dạng chữ thường, bỏ khoảng trắng; duy nhất |
| `phone` | Không bắt buộc; số di động VN: `0[35789]xxxxxxxx`, chấp nhận `+84`/`84`, dấu cách, `.`, `-`; lưu dạng `0xxxxxxxxx`; duy nhất |
| `password` | 8–72 ký tự, có ít nhất 1 chữ thường, 1 chữ hoa, 1 chữ số và 1 ký tự đặc biệt; không chứa khoảng trắng. Chỉ áp dụng khi đặt mật khẩu mới (đăng ký, đổi, đặt lại, admin tạo); mật khẩu cũ vẫn đăng nhập được |
| `fullName` | 2–150 ký tự |
| `birthDate` | `yyyy-MM-dd`, phải ở quá khứ, từ 1900 |
| `tieuSu` | Xem bảng ở mục 3 |

Email và SĐT của tài khoản đã xoá mềm được giải phóng để đăng ký lại.

## 5. Endpoint `/api/auth` (công khai)

### POST `/api/auth/register` → 201 `AuthResponse`

```json
{ "fullName": "Nguyễn Văn A", "email": "a@example.com", "phone": "0912345678", "password": "matkhau123" }
```

Lỗi: 400 `VALIDATION_FAILED`, 409 `EMAIL_TAKEN` / `PHONE_TAKEN`.

### POST `/api/auth/login` → 200 `AuthResponse`

```json
{ "identifier": "a@example.com hoặc 0912345678", "password": "matkhau123" }
```

| Lỗi | Khi nào |
|---|---|
| 401 `INVALID_CREDENTIALS` | Sai tài khoản hoặc mật khẩu (cùng một thông báo, không lộ tài khoản có tồn tại hay không) |
| 423 `ACCOUNT_LOCKED` | Sai 5 lần liên tiếp → khoá 15 phút; thông báo kèm số phút còn lại |
| 403 `ACCOUNT_DISABLED` | ADMIN đã khoá tài khoản |

Đăng nhập thành công hoặc đặt lại mật khẩu sẽ xoá bộ đếm sai.

### POST `/api/auth/refresh` → 200 `AuthResponse`

```json
{ "refreshToken": "..." }
```

Lỗi: 401 `INVALID_REFRESH_TOKEN` (không tồn tại, hết hạn, đã thu hồi), 403 `ACCOUNT_DISABLED`.

### POST `/api/auth/logout` → 204

```json
{ "refreshToken": "..." }
```

Luôn trả 204, kể cả khi token không tồn tại.

### Quên mật khẩu (3 bước)

**1. POST `/api/auth/forgot-password` → 202**

```json
{ "email": "a@example.com" }
```

```json
{ "message": "Nếu email đã được đăng ký, mã OTP đã được gửi tới hộp thư. Mã có hiệu lực trong 5 phút." }
```

- Luôn trả cùng một phản hồi dù email có tồn tại hay không.
- OTP gồm 6 chữ số, hiệu lực 5 phút, gửi bằng email HTML tiếng Việt (bất đồng bộ).
- Chỉ gửi lại được sau 60 giây; gọi sớm hơn vẫn trả 202 nhưng không gửi mã mới.
- Gửi mã mới sẽ vô hiệu mã cũ.

**2. POST `/api/auth/verify-otp` → 200**

```json
{ "email": "a@example.com", "otp": "123456" }
```

```json
{ "resetToken": "...", "expiresIn": 900 }
```

Lỗi:
- 400 `INVALID_OTP`: sai, hết hạn hoặc đã dùng.
- 400 `OTP_ATTEMPTS_EXCEEDED`: sai đến lần thứ 5 → mã bị huỷ, phải yêu cầu mã mới.

Mỗi OTP chỉ đổi được một lần lấy `resetToken`.

**3. POST `/api/auth/reset-password` → 200**

```json
{ "resetToken": "...", "newPassword": "matkhaumoi456" }
```

- `resetToken` dùng một lần, hiệu lực 15 phút.
- Thành công thì mọi refresh token bị thu hồi; người dùng phải đăng nhập lại.
- Lỗi: 400 `INVALID_RESET_TOKEN`, 400 `VALIDATION_FAILED`.

## 6. Endpoint `/api/users/me` (mọi vai trò đã đăng nhập)

| Method | Path | Mô tả | Thành công |
|---|---|---|---|
| GET | `/api/users/me` | Thông tin tài khoản | 200 `UserResponse` |
| PUT | `/api/users/me` | Cập nhật tài khoản và hồ sơ bệnh nhân | 200 `UserResponse` |
| PUT | `/api/users/me/password` | Đổi mật khẩu | 204 |

`PUT /api/users/me` **ghi đè toàn bộ** hồ sơ, nên trường bỏ trống sẽ thành `null`; riêng `tieuSu` không gửi (hoặc `null`) thì giữ nguyên, gửi `{}` thì xoá hết. `fullName` và `email` là bắt buộc; `code` không sửa được.

```json
{ "fullName": "Nguyễn Văn A", "email": "a@example.com", "phone": "0912345678",
  "birthDate": "1980-05-20", "gender": "MALE",
  "tieuSu": { "cviec": "Thợ nghiền đá", "tuoinghe": "12,5", "hutthuoc": "Khong hut thuoc" } }
```

`PUT /api/users/me/password`:

```json
{ "currentPassword": "matkhau123", "newPassword": "matkhaumoi456" }
```

- Lỗi: 400 `WRONG_PASSWORD`, 400 `SAME_PASSWORD`.
- Thành công thì mọi refresh token bị thu hồi.

## 7. Endpoint quản trị `/api/users` (chỉ `ADMIN`)

| Method | Path | Body | Thành công |
|---|---|---|---|
| GET | `/api/users?role=&q=&page=0&size=20&sort=createdAt&direction=DESC` | — | 200 `PageResponse<UserResponse>` |
| GET | `/api/users/{id}` | — | 200 `UserResponse` |
| POST | `/api/users` | `{fullName, email, phone?, password}` | 201 + header `Location`; tạo `USER` kèm mã bệnh nhân |
| PATCH | `/api/users/{id}/status` | `{"enabled": false}` | 200 `UserResponse` |
| DELETE | `/api/users/{id}` | — | 204 (xoá mềm) |

Tham số danh sách:
- `q`: tìm không phân biệt hoa thường trong email, họ tên và SĐT.
- `size`: 1–100.
- `sort`: `createdAt` | `fullName` | `email` | `role`.
- `direction`: `ASC` | `DESC`.

```json
{ "content": [ ...UserResponse ], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3 }
```

Ràng buộc:
- ADMIN không tự khoá hoặc tự xoá chính mình → 400 `CANNOT_MODIFY_SELF`.
- `enabled=true` đồng thời xoá khoá tạm do đăng nhập sai.
- `enabled=false` và xoá đều thu hồi refresh token.
- Xoá mềm: giữ bản ghi cùng hồ sơ và lượt chẩn đoán (dữ liệu y tế), ẩn khỏi mọi API; `GET` sẽ trả 404.

Gọi bằng vai trò khác ADMIN → 403 `FORBIDDEN`.

## 8. Định dạng lỗi

Mọi lỗi, kể cả 401/403 từ Spring Security, đều có dạng:

```json
{
  "timestamp": "2026-10-07T16:57:43.937Z",
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Dữ liệu không hợp lệ.",
  "path": "/api/auth/register",
  "fieldErrors": { "email": "Email không hợp lệ.", "password": "..." }
}
```

`fieldErrors` chỉ có khi lỗi validation. `message` là tiếng Việt, hiển thị trực tiếp cho người dùng được.

| HTTP | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `BAD_REQUEST`, `INVALID_OTP`, `OTP_ATTEMPTS_EXCEEDED`, `INVALID_RESET_TOKEN`, `WRONG_PASSWORD`, `SAME_PASSWORD`, `CANNOT_MODIFY_SELF` |
| 401 | `UNAUTHORIZED` (thiếu hoặc sai access token), `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` |
| 403 | `FORBIDDEN` (không đủ quyền), `ACCOUNT_DISABLED` |
| 404 | `NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` (sai kiểu nội dung, VD gửi JSON thường vào endpoint multipart) |
| 409 | `EMAIL_TAKEN`, `PHONE_TAKEN`, `CONFLICT` |
| 423 | `ACCOUNT_LOCKED` |
| 500 | `INTERNAL_ERROR` |

## 9. CORS

Cho phép cho `/api/**` từ `http://localhost:3000` (web), `http://localhost:8081`, `http://localhost:19006` và `exp://*` (Expo).
Đổi danh sách này qua `app.cors.allowed-origin-patterns`.

## 10. Bảo mật & dữ liệu cá nhân

- Mật khẩu và OTP lưu bằng BCrypt; refresh token và reset token lưu bằng SHA-256. Không lưu giá trị gốc.
- Log không chứa mật khẩu, OTP, token, email, SĐT hay họ tên; `toString()` của các DTO nhạy cảm trả `[***]`.
- Bí mật (DB, JWT, SMTP, admin) lấy từ biến môi trường hoặc `.env` (đã nằm trong `.gitignore`).
- SMTP Gmail: `MAIL_HOST=smtp.gmail.com`, `MAIL_PORT=587`, `MAIL_SMTP_AUTH=true`, `MAIL_STARTTLS=true`, `MAIL_PASSWORD` = App Password.
