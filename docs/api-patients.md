# SilicoCare API — Hồ sơ bệnh nhân

Base URL dev: `http://localhost:8080` · Swagger UI: `/swagger-ui.html` (nhóm **Hồ sơ bệnh nhân**) · OpenAPI JSON: `/v3/api-docs`

Mọi endpoint đều cần đăng nhập (`Authorization: Bearer <accessToken>`). Định dạng lỗi, token và vai trò: xem [api-auth.md](api-auth.md).

Tài liệu này chỉ gồm thông tin hành chính và tiền sử. Lượt khám, chỉ số lâm sàng và gắn ảnh: xem [api-visits.md](api-visits.md). **Chưa có** dự đoán.

## 1. Mô hình

- Mỗi tài khoản `USER` có **đúng một** hồ sơ. Hồ sơ được tạo trong cùng transaction với tài khoản, khi tự đăng ký (`POST /api/auth/register`) và khi ADMIN tạo user (`POST /api/users`).
- Hồ sơ mới lấy sẵn `fullName`, `birthDate`, `gender`, `occupation`, `phone` từ tài khoản. Các trường còn lại để trống.
- Tài khoản `ADMIN` không có hồ sơ.
- ADMIN có thể tạo thêm hồ sơ không gắn tài khoản (`userId = null`).
- `code` là mã hiển thị dạng `BN-000001`, do server sinh từ sequence `patient_code_seq`. Client không gửi được và không đổi được `code`.
- Xoá là xoá mềm. Hồ sơ đã xoá biến khỏi mọi API.

### Xoá hồ sơ và xoá tài khoản

| Thao tác | Kết quả |
|---|---|
| `DELETE /api/patients/{id}` khi hồ sơ gắn tài khoản **đang hoạt động** | 409 `PATIENT_LINKED_TO_ACCOUNT`, không xoá gì. Nhờ vậy mỗi USER luôn có đúng một hồ sơ và `GET /me` không thể rơi vào 404 vĩnh viễn |
| `DELETE /api/users/{id}` (xoá mềm tài khoản) | Hồ sơ **được giữ nguyên, có chủ ý**, để giữ dữ liệu y tế. Hồ sơ vẫn hiện trong danh sách của ADMIN, `userId` vẫn trỏ tới tài khoản đã xoá, và việc đồng bộ dừng lại |
| `DELETE /api/patients/{id}` sau khi tài khoản đã bị xoá | 204, xoá mềm như hồ sơ không gắn tài khoản |

Muốn bỏ hẳn một bệnh nhân có tài khoản: xoá tài khoản trước, rồi mới xoá hồ sơ.

### Đồng bộ với tài khoản

Chỉ 4 trường được đồng bộ: **`fullName`, `birthDate`, `gender`, `occupation`**.

| Thao tác | Kết quả |
|---|---|
| `PUT /api/users/me` | Ghi 4 trường sang hồ sơ của tài khoản |
| `PUT /api/patients/{id}` trên hồ sơ có `userId` (do chủ hồ sơ hoặc ADMIN sửa) | Ghi 4 trường sang tài khoản |

`phone` và `email` **không** đồng bộ. SĐT của tài khoản là duy nhất và dùng để đăng nhập; SĐT của hồ sơ chỉ là thông tin liên hệ, không bắt buộc duy nhất.

## 2. `PatientResponse`

```json
{
  "id": "2f6c1c1e-8d0a-4c55-9a51-3f2a1d7e9b10",
  "code": "BN-000001",
  "userId": "5bb190d9-06cc-4427-a8de-339b40c8964d",
  "fullName": "Nguyễn Văn A",
  "birthDate": "1980-05-20",
  "gender": "MALE",
  "phone": "0912345678",
  "occupation": "Thợ mỏ",
  "workplace": "Công ty khai thác đá X",
  "exposureHistory": "10 năm khoan đá, không đeo khẩu trang",
  "riskFactors": "Hút thuốc lá",
  "createdAt": "2026-10-17T08:00:00Z",
  "updatedAt": "2026-10-17T08:00:00Z"
}
```

- `gender`: `MALE` | `FEMALE` | `OTHER` | `null`.
- `userId`: `null` với hồ sơ do ADMIN tạo.
- Trường trống trả về `null`.

Đối chiếu với web (`src/types/patient.ts`):

| Web | API |
|---|---|
| `name` | `fullName` |
| `gender` `male`/`female`/`other` | `MALE`/`FEMALE`/`OTHER` |
| `birthDate`, `phone`, `occupation`, `workplace`, `exposureHistory`, `riskFactors` | giữ nguyên tên |
| `id` (mock dạng `BN-...`) | `id` là UUID; mã hiển thị là `code` |
| `clinicalRecords` | lượt khám, lấy riêng qua `GET /api/patients/{id}/visits` ([api-visits.md](api-visits.md)) |

## 3. `PatientRequest` (tạo và sửa)

```json
{
  "fullName": "Nguyễn Văn A",
  "birthDate": "1980-05-20",
  "gender": "MALE",
  "phone": "0912345678",
  "occupation": "Thợ mỏ",
  "workplace": "Công ty khai thác đá X",
  "exposureHistory": "...",
  "riskFactors": "..."
}
```

`PUT` **ghi đè toàn bộ**: trường không gửi hoặc gửi rỗng sẽ thành `null`. Nếu gửi kèm `id`, `code`, `userId` hoặc `createdAt` thì các trường đó bị bỏ qua.

> **Lưu ý cho FE:** luôn gửi **đủ cả form** khi sửa, gồm cả các trường người dùng không đụng tới. Thiếu `phone`, `workplace`, `exposureHistory` hay `riskFactors` thì dữ liệu cũ của trường đó bị xoá. Cách an toàn: nạp `GET` vào form, sửa trên đó, rồi gửi lại toàn bộ.

| Trường | Quy tắc |
|---|---|
| `fullName` | Bắt buộc, 2–150 ký tự (bỏ khoảng trắng hai đầu) |
| `birthDate` | Bắt buộc, `yyyy-MM-dd`, từ `1900-01-01` tới trước hôm nay |
| `gender` | Không bắt buộc; `MALE` / `FEMALE` / `OTHER` |
| `phone` | Không bắt buộc; số di động VN như tài khoản (`0[35789]xxxxxxxx`, chấp nhận `+84`, dấu cách, `.`, `-`); lưu dạng `0xxxxxxxxx`; **không** cần duy nhất |
| `occupation` | Bắt buộc, ≤ 150 ký tự |
| `workplace` | Không bắt buộc, ≤ 255 ký tự |
| `exposureHistory`, `riskFactors` | Không bắt buộc, mỗi trường ≤ 2000 ký tự (đổi qua `APP_PATIENT_MAX_TEXT_LENGTH`) |

Quy tắc lấy theo `validatePatient` của web; backend là nơi kiểm tra cuối cùng.

Hồ sơ tự tạo khi đăng ký có thể đang thiếu `birthDate`/`occupation`. Người dùng phải điền đủ hai trường này ở lần sửa hồ sơ đầu tiên. Màn hình web nên nạp `GET /api/patients/me` rồi mở form để người dùng điền tiếp.

## 4. Endpoint `/api/patients`

| Method | Path | Ai được gọi | Thành công |
|---|---|---|---|
| GET | `/api/patients/me` | Đã đăng nhập (USER) | 200 `PatientResponse` |
| GET | `/api/patients?q=&page=0&size=20&sort=createdAt&direction=DESC` | ADMIN | 200 `PageResponse<PatientResponse>` |
| POST | `/api/patients` | ADMIN | 201 `PatientResponse` + header `Location` |
| GET | `/api/patients/{id}` | ADMIN hoặc chủ hồ sơ | 200 `PatientResponse` |
| PUT | `/api/patients/{id}` | ADMIN hoặc chủ hồ sơ | 200 `PatientResponse` |
| DELETE | `/api/patients/{id}` | ADMIN | 204 (xoá mềm); 409 nếu gắn tài khoản đang hoạt động |

### Phân quyền

- **Hồ sơ không tồn tại, đã bị xoá, hoặc không phải của mình → 404 `PATIENT_NOT_FOUND`**, không phải 403, để không lộ hồ sơ có tồn tại hay không.
- `GET /api/patients/me` do ADMIN gọi → 404 `PATIENT_NOT_FOUND`.
- USER gọi endpoint chỉ dành cho ADMIN (`GET /api/patients`, `POST`, `DELETE`) → 403 `FORBIDDEN`. Lỗi này lộ quyền của endpoint, không lộ dữ liệu.
- Tài khoản bị khoá → 403 `ACCOUNT_DISABLED`.

### Tham số danh sách

- `q`: tìm trong họ tên, mã, SĐT và nghề nghiệp.
  - Không phân biệt hoa thường.
  - Không phân biệt dấu tiếng Việt: `nguyen thi`, `NGUYỄN THỊ` và `Nguyen Thi` cho cùng kết quả.
  - Tìm theo mã: `bn-000001` hoặc một phần như `000001`.
  - SĐT so khớp theo dạng đã lưu `0xxxxxxxxx`, ví dụ `0912`.
- `size`: 1–100.
- `sort`: `createdAt` | `fullName` | `code`.
- `direction`: `ASC` | `DESC`.

```json
{ "content": [ ...PatientResponse ], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3 }
```

### Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Dữ liệu không hợp lệ. `fieldErrors` có key là tên trường, ví dụ `{"birthDate": "Ngày sinh phải ở quá khứ."}` |
| 400 | `BAD_REQUEST` | JSON hỏng, `gender` hoặc `sort` sai giá trị, `id` không phải UUID |
| 401 | `UNAUTHORIZED` | Thiếu hoặc sai access token |
| 403 | `FORBIDDEN` | USER gọi endpoint của ADMIN |
| 403 | `ACCOUNT_DISABLED` | Tài khoản bị khoá |
| 404 | `PATIENT_NOT_FOUND` | Xem mục **Phân quyền** |
| 409 | `PATIENT_LINKED_TO_ACCOUNT` | Xoá hồ sơ đang gắn tài khoản còn hoạt động |

## 5. Ví dụ luồng

```text
POST /api/auth/register           → tài khoản USER + hồ sơ BN-000123
GET  /api/patients/me             → hồ sơ, workplace/exposureHistory/riskFactors = null
PUT  /api/patients/{id}           → điền đủ; fullName/birthDate/gender/occupation chép sang tài khoản
PUT  /api/users/me (đổi họ tên)   → GET /api/patients/me thấy họ tên mới
```

## 6. Dữ liệu cá nhân

- Không log họ tên, ngày sinh, SĐT, tiền sử phơi nhiễm. `toString()` của entity và DTO không in nội dung.
- Test và seed chỉ dùng dữ liệu giả.
- Bảng `patients` và sequence `patient_code_seq` nằm trong `V1__init_auth.sql`. DB dev tạo từ bản V1 cũ hơn sẽ báo lỗi checksum của Flyway; khi đó đặt lại DB: `docker compose down -v` rồi `docker compose --env-file .env up -d`. Lệnh này xoá toàn bộ dữ liệu dev.
