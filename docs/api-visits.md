# SilicoCare API — Lượt khám (bản demo)

Base URL dev: `http://localhost:8080` · Swagger UI: `/swagger-ui.html` (nhóm **Lượt khám**) · OpenAPI JSON: `/v3/api-docs`

Mọi endpoint đều cần đăng nhập (`Authorization: Bearer <accessToken>`). Định dạng lỗi, token và vai trò: xem [api-auth.md](api-auth.md). Hồ sơ bệnh nhân: [api-patients.md](api-patients.md). Upload ảnh: [api-images.md](api-images.md).

Phạm vi: lượt khám trên hồ sơ bệnh nhân, mỗi lượt khám gắn **tối đa một** ảnh X-quang. Mỗi lần người dùng gửi ảnh và/hoặc chỉ số lâm sàng là **một lượt khám mới**, lưu kèm thời điểm gửi (`createdAt`); danh sách lượt khám chính là lịch sử của bệnh nhân. Khi lượt khám có ảnh, server gọi mô hình ảnh (service FastAPI trong `predict/`) và trả kết quả **thử nghiệm** ở `latestPrediction` (mục 8). Chỉ số lâm sàng chỉ được lưu, **chưa** đưa vào mô hình. Chưa có danh mục ngành/tỉnh.

## 1. Mô hình

- **Lịch sử = các lần gửi.** Mỗi lần gửi tạo một lượt khám mới, không ghi đè lần trước. `createdAt` là thời điểm server nhận (UTC); `visitDate` là ngày khám, nếu không gửi thì server đặt bằng ngày gửi. Dùng `createdAt` để sắp xếp và hiển thị giờ gửi.
- Một lần gửi có thể chỉ có ảnh, chỉ có chỉ số, hoặc cả hai. Gửi rỗng → 400 `EMPTY_SUBMISSION`. Ngành, tuổi nghề, tỉnh chỉ cần đủ ở bước dự đoán (sau này), không bắt buộc khi lưu.
- Một hồ sơ có nhiều lượt khám. Lượt khám thuộc cố định một hồ sơ, không chuyển sang hồ sơ khác được.
- **Tuổi và giới tính không lưu ở lượt khám**, mà lấy từ hồ sơ. Hồ sơ thiếu ngày sinh vẫn gửi được (`ageAtVisit = null`); `gender` và `birthDate` sẽ phải có đủ ở bước dự đoán.
- Response có thêm hai trường **tính khi trả về, không lưu DB**:
  - `ageAtVisit`: số tuổi tròn năm tại `visitDate`, tính theo `birthDate` hiện tại của hồ sơ. Sửa ngày sinh ở hồ sơ thì tuổi của mọi lượt khám đổi theo.
  - `bmi`: `weightKg / (heightCm/100)²`, làm tròn 1 chữ số thập phân; `null` nếu thiếu chiều cao hoặc cân nặng.
- Ảnh gắn qua cột `images.visit_id`. Một ảnh thuộc tối đa một lượt khám và một lượt khám có tối đa một ảnh.
- Xoá là xoá mềm. Lượt khám đã xoá biến khỏi mọi API.

## 2. `VisitResponse`

```json
{
  "id": "8a1f0c52-3f7e-4c1e-9b1a-2c7e5d9f0a11",
  "patientId": "2f6c1c1e-8d0a-4c55-9a51-3f2a1d7e9b10",
  "patient": { "id": "2f6c1c1e-8d0a-4c55-9a51-3f2a1d7e9b10", "code": "BN-000123", "fullName": "Nguyễn Văn A" },
  "visitDate": "2025-06-20",
  "industry": "Khai thác đá",
  "yearsInJob": 10,
  "province": "Ninh Bình",
  "yearsExposed": 8,
  "heightCm": 170,
  "weightKg": 65,
  "systolicBp": 120,
  "diastolicBp": 80,
  "pulse": 75,
  "smoker": true,
  "cigarettesPerDay": 10,
  "fvcL": 3.5,
  "fev1L": 2.8,
  "extra": { "ghiChu": "..." },
  "schemaVersion": 1,
  "ageAtVisit": 45,
  "bmi": 22.5,
  "image": {
    "id": "d2388bce-65e1-47a6-ae03-c40cd8783a43",
    "contentType": "image/png",
    "sizeBytes": 34567,
    "width": 1600,
    "height": 1200
  },
  "latestPrediction": {
    "id": "5b0e9a77-1c2d-4e3f-8a9b-0c1d2e3f4a5b",
    "visitId": "8a1f0c52-3f7e-4c1e-9b1a-2c7e5d9f0a11",
    "imageId": "d2388bce-65e1-47a6-ae03-c40cd8783a43",
    "status": "SUCCESS",
    "probability": 0.27,
    "label": "Khong",
    "threshold": 0.403,
    "modelName": "BioViL-T fine-tune Set A (bnn)",
    "modelVersion": "c63f9782b7d1",
    "errorCode": null,
    "createdAt": "2026-10-17T08:00:02Z",
    "experimental": true,
    "disclaimer": "Kết quả thử nghiệm, không thay thế chẩn đoán của bác sĩ."
  },
  "createdAt": "2026-10-17T08:00:00Z",
  "updatedAt": "2026-10-17T08:00:00Z"
}
```

- Trường trống trả về `null`; `extra` trống trả về `{}`.
- `image` là `null` khi chưa gắn ảnh. Tải nội dung ảnh qua `GET /api/images/{image.id}/content`.
- `schemaVersion` do server quản lý (hiện là `1`). Client gửi lên thì bị bỏ qua.
- `patient`: tóm tắt hồ sơ (`id`, `code`, `fullName`) để hiển thị danh sách mà không phải gọi thêm API hồ sơ.
- `latestPrediction`: kết quả dự đoán mới nhất **của ảnh đang gắn**; `null` khi chưa có ảnh, chưa dự đoán, hoặc đã gỡ ảnh. Chi tiết ở mục 8.

## 3. `VisitRequest` (tạo và sửa)

```json
{
  "visitDate": "2025-06-20",
  "industry": "Khai thác đá",
  "yearsInJob": 10,
  "province": "Ninh Bình",
  "yearsExposed": 8,
  "heightCm": 170,
  "weightKg": 65,
  "systolicBp": 120,
  "diastolicBp": 80,
  "pulse": 75,
  "smoker": true,
  "cigarettesPerDay": 10,
  "fvcL": 3.5,
  "fev1L": 2.8,
  "extra": {}
}
```

`PUT` **ghi đè toàn bộ**: trường không gửi sẽ thành `null` (riêng `extra` thành `{}`; `visitDate` không gửi thì giữ nguyên). Nếu gửi kèm `id`, `patientId`, `schemaVersion`, `ageAtVisit`, `bmi` hoặc `image` thì các trường đó bị bỏ qua.

> **Lưu ý cho FE:** như hồ sơ, luôn gửi **đủ cả form** khi sửa. Cách an toàn: nạp `GET /api/visits/{id}` vào form, sửa trên đó, rồi gửi lại toàn bộ.

| Trường | Quy tắc |
|---|---|
| `visitDate` | Không bắt buộc (mặc định ngày gửi), `yyyy-MM-dd`, không ở tương lai, không trước ngày sinh của hồ sơ. Khi sửa mà không gửi thì giữ nguyên |
| `industry` | Không bắt buộc, ≤ 100 ký tự, chuỗi tự do |
| `yearsInJob` | Không bắt buộc, 0–60 (năm, cho phép số lẻ) |
| `province` | Không bắt buộc, ≤ 50 ký tự, chuỗi tự do |
| `yearsExposed` | Không bắt buộc, 0–60 |
| `heightCm` | Không bắt buộc, 100–220 |
| `weightKg` | Không bắt buộc, 20–200 |
| `systolicBp` | Không bắt buộc, số nguyên 60–260 (mmHg) |
| `diastolicBp` | Không bắt buộc, số nguyên 30–160 (mmHg) |
| `pulse` | Không bắt buộc, số nguyên 30–220 (lần/phút) |
| `smoker` | Không bắt buộc; `true` / `false` / `null` |
| `cigarettesPerDay` | Không bắt buộc, 0–100. **Chỉ lưu khi `smoker = true`**; các trường hợp khác server lưu `null` |
| `fvcL` | Không bắt buộc, 0,3–8 (lít) |
| `fev1L` | Không bắt buộc, 0,2–8 (lít) |
| `imageId` | Không bắt buộc, **chỉ khi tạo**: gắn ảnh đã upload ngay trong lần gửi (cùng quy tắc như `PUT /visits/{id}/image`; lỗi thì không tạo lượt khám). Khi sửa bị bỏ qua |
| `extra` | Không bắt buộc. Phải là **object JSON** (không phải mảng hay giá trị đơn), tối đa 8 KB khi tuần tự hoá, lồng tối đa 3 cấp. Đổi qua `APP_VISIT_EXTRA_MAX_SIZE`, `APP_VISIT_EXTRA_MAX_DEPTH` |

Quy tắc liên trường (chỉ kiểm tra khi có đủ cả hai giá trị):

- `systolicBp` phải **lớn hơn** `diastolicBp`; sai thì lỗi nằm ở `fieldErrors.systolicBp`.
- `fev1L` phải **≤** `fvcL`; sai thì lỗi nằm ở `fieldErrors.fev1L`.

Khoảng giá trị khớp ràng buộc `CHECK` của bảng `visits` trong `V1__init_auth.sql`.

Cách đếm cấp lồng của `extra`: bản thân object gốc là cấp 1, mỗi object hoặc mảng lồng bên trong tính thêm một cấp. Ví dụ `{"a":{"b":[1,2]}}` có 3 cấp (hợp lệ), còn `{"a":{"b":{"c":{}}}}` có 4 cấp (bị từ chối).

## 4. Endpoint

| Method | Path | Thành công |
|---|---|---|
| POST | `/api/patients/{patientId}/visits` | 201 `VisitResponse` + header `Location: /api/visits/{id}` |
| GET | `/api/patients/{patientId}/visits?page=0&size=20` | 200 `PageResponse<VisitResponse>`, mới nhất trước |
| GET | `/api/visits/{id}` | 200 `VisitResponse` |
| PUT | `/api/visits/{id}` | 200 `VisitResponse` |
| DELETE | `/api/visits/{id}` | 204 (xoá mềm; ảnh đang gắn được gỡ ra, **không** bị xoá) |
| PUT | `/api/visits/{id}/image` | 200 `VisitResponse` (đã có `image`) |
| DELETE | `/api/visits/{id}/image` | 204 (chỉ gỡ ảnh, không xoá ảnh) |
| POST | `/api/visits/{id}/predictions` | 201 `PredictionResponse` (chạy lại dự đoán, mục 8) |
| GET | `/api/visits?page=0&size=20` | 200 `PageResponse<VisitResponse>` của **mọi** bệnh nhân, chỉ ADMIN |

### Phân quyền

- Chủ hồ sơ (USER) và ADMIN đều được tạo, xem, sửa, xoá lượt khám và gắn/gỡ ảnh.
- Người khác nhận 404, không phải 403, để không lộ hồ sơ hay lượt khám có tồn tại hay không:
  - với endpoint theo hồ sơ (`/api/patients/{patientId}/visits`) → 404 `PATIENT_NOT_FOUND`;
  - với endpoint theo lượt khám (`/api/visits/{id}`) → 404 `VISIT_NOT_FOUND`.
- Lượt khám của hồ sơ đã bị xoá mềm cũng trả 404 `VISIT_NOT_FOUND`.
- `GET /api/visits` (toàn hệ thống) chỉ dành cho ADMIN; USER nhận 403 `FORBIDDEN`. Lượt khám đã xoá hoặc thuộc hồ sơ đã xoá không xuất hiện.

### Danh sách

- Sắp xếp cố định: `visitDate` giảm dần, cùng ngày thì lượt tạo sau đứng trước.
- `page` bắt đầu từ 0; `size` từ 1 đến 100.

```json
{ "content": [ ...VisitResponse ], "page": 0, "size": 20, "totalElements": 3, "totalPages": 1 }
```

### Gắn ảnh: `PUT /api/visits/{id}/image`

```json
{ "imageId": "d2388bce-65e1-47a6-ae03-c40cd8783a43" }
```

Luồng: upload ảnh qua `POST /api/images` để lấy `id`, rồi gắn vào lượt khám.

| Tình huống | Kết quả |
|---|---|
| Ảnh hợp lệ, lượt khám chưa có ảnh | 200, ảnh được gắn |
| Gắn lại đúng ảnh đang gắn | 200, không đổi gì |
| Lượt khám đã có **ảnh khác** | 409 `VISIT_ALREADY_HAS_IMAGE`. Muốn thay ảnh: `DELETE /api/visits/{id}/image` rồi gắn ảnh mới |
| Ảnh đã gắn vào **lượt khám khác** | 409 `IMAGE_ALREADY_ATTACHED` |
| Ảnh không tồn tại, đã xoá, hoặc không phải của người thao tác (trừ ADMIN) | 404 `NOT_FOUND` |
| Thiếu `imageId` | 400 `VALIDATION_FAILED`, `fieldErrors.imageId` |

ADMIN gắn được ảnh của bất kỳ ai. USER chỉ gắn được ảnh do chính mình upload.

### Gỡ ảnh: `DELETE /api/visits/{id}/image`

Luôn trả 204, kể cả khi lượt khám chưa có ảnh. Ảnh vẫn còn trong `GET /api/images` và gắn lại được sang lượt khám khác.

### Liên quan tới API ảnh

- `ImageResponse` có thêm `visitId` (`null` nếu chưa gắn).
- `DELETE /api/images/{id}` vẫn xoá được ảnh đang gắn; liên kết với lượt khám được gỡ trong cùng transaction.

### Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Dữ liệu không hợp lệ, kể cả lỗi liên trường và `extra`. `fieldErrors` có key là tên trường |
| 400 | `EMPTY_SUBMISSION` | Gửi lượt khám không có ảnh, không có chỉ số nào và `extra` trống |
| 400 | `BAD_REQUEST` | JSON hỏng, sai kiểu (ví dụ chữ trong `systolicBp`), `id` không phải UUID |
| 401 | `UNAUTHORIZED` | Thiếu hoặc sai access token |
| 403 | `ACCOUNT_DISABLED` | Tài khoản bị khoá |
| 404 | `PATIENT_NOT_FOUND` | Hồ sơ không tồn tại hoặc không có quyền (endpoint theo hồ sơ) |
| 404 | `VISIT_NOT_FOUND` | Lượt khám không tồn tại, đã xoá, hoặc không có quyền |
| 404 | `NOT_FOUND` | Ảnh không tồn tại hoặc không có quyền dùng (khi gắn ảnh) |
| 409 | `VISIT_ALREADY_HAS_IMAGE` | Lượt khám đã có ảnh khác |
| 409 | `IMAGE_ALREADY_ATTACHED` | Ảnh đã gắn vào lượt khám khác |
| 400 | `VISIT_HAS_NO_IMAGE` | `POST /api/visits/{id}/predictions` khi lượt khám chưa gắn ảnh |
| 403 | `FORBIDDEN` | USER gọi `GET /api/visits` |
| 503 | `PREDICT_DISABLED` | `POST /api/visits/{id}/predictions` khi `PREDICT_ENABLED=false` |

Lỗi khi gọi mô hình **không** làm hỏng request: lượt khám vẫn được lưu và kết quả FAILED nằm trong `latestPrediction.errorCode` (mục 8).

## 5. Ví dụ luồng

```text
POST /api/auth/register                    → tài khoản USER + hồ sơ
GET  /api/patients/me                      → lấy patientId
POST /api/images                           → imageId
POST /api/patients/{patientId}/visits      → 201 {"imageId": …, "extra": {…triệu chứng…}}; createdAt = thời điểm gửi
GET  /api/patients/{patientId}/visits      → lịch sử các lần gửi, mới nhất trước
PUT  /api/visits/{visitId}/image           → (cách khác) gắn ảnh vào lượt khám đã có
PUT  /api/visits/{visitId}/image (ảnh 2)   → 409 VISIT_ALREADY_HAS_IMAGE
DELETE /api/visits/{visitId}               → 204; GET /api/images/{imageId} vẫn 200, visitId = null
```

## 6. Dữ liệu cá nhân

- Không log giá trị chỉ số lâm sàng hay nội dung `extra` ở mức INFO trở lên. Entity `Visit` không có `toString()`; `VisitRequest.toString()` trả `VisitRequest[***]`.
- Test và seed chỉ dùng dữ liệu giả.
- Bảng `visits` và khoá ngoại `images.visit_id` có trong `V1__init_auth.sql`; bảng `predictions` thêm ở `V2__predictions.sql` (Flyway tự chạy khi khởi động lại backend, không mất dữ liệu). DB dev tạo từ bản V1 cũ hơn sẽ báo lỗi checksum của Flyway; khi đó đặt lại DB: `docker compose down -v` rồi `docker compose --env-file .env up -d`. Lệnh này xoá toàn bộ dữ liệu dev.

## 7. Bản demo, sẽ chỉnh lại

- **`industry` và `province` là chuỗi tự do**, chưa có danh mục và chưa chuẩn hoá. `"Ninh Bình"`, `"ninh binh"` và `"Tỉnh Ninh Bình"` được lưu như ba giá trị khác nhau. Khi nối mô hình cần thêm danh mục hoặc bảng ánh xạ.
- **Huyết áp tâm thu/tâm trương** (`systolicBp`, `diastolicBp`) và khoảng giá trị của chúng **chưa đối chiếu với từ điển dữ liệu gốc**. Tên trường, đơn vị và khoảng có thể đổi.
- Lượt khám thiếu ngành / tuổi nghề / tỉnh vẫn lưu được; việc kiểm tra đủ trường để dự đoán sẽ làm ở endpoint dự đoán.
- **`extra` chưa có schema.** Server chỉ kiểm tra đó là object, kích thước và độ sâu; không kiểm tra key hay kiểu giá trị. `schemaVersion` có sẵn để sau này phân biệt các phiên bản schema.
- **Quyền của ADMIN khác bản mock của web.** Mock web không cho ADMIN sửa dữ liệu lâm sàng; bản demo này cho ADMIN tạo, sửa, xoá lượt khám và gắn ảnh của bất kỳ ai. Cần chốt lại khi có vai trò bác sĩ.
- Tuổi tính theo ngày sinh **hiện tại** của hồ sơ, không chụp lại tại thời điểm khám.
- Dự đoán chỉ dùng ảnh. Mô hình mới được kiểm định trên một bộ dữ liệu (Set A); với ảnh từ nguồn khác kết quả có thể lệch nhiều. Luôn hiển thị `disclaimer`.

## 8. Dự đoán (thử nghiệm)

### Khi nào chạy

- **Tự động** sau `POST /api/patients/{patientId}/visits` có `imageId`, và sau `PUT /api/visits/{id}/image`, nếu ảnh đang gắn chưa có kết quả nào. Gắn lại đúng ảnh cũ không chạy lại.
- **Thủ công**: `POST /api/visits/{id}/predictions` (không có body) luôn chạy một lần mới, dùng cho nút "Thử lại" khi lần trước FAILED. Quyền giống `GET /api/visits/{id}` (người khác nhận 404 `VISIT_NOT_FOUND`).
- Lượt khám chỉ có chỉ số lâm sàng thì không gọi mô hình; `latestPrediction = null`.
- Request chờ mô hình trả lời (tối đa `PREDICT_TIMEOUT`, mặc định 30 giây). FE nên hiện trạng thái đang xử lý.

### `PredictionResponse`

| Trường | Ý nghĩa |
|---|---|
| `status` | `SUCCESS` hoặc `FAILED` |
| `probability` | Xác suất mô hình cho là **có** bụi phổi silic, 0–1. `null` khi FAILED |
| `threshold` | Ngưỡng quyết định (hiện 0,403). `label = "Co"` khi `probability ≥ threshold` |
| `label` | `"Co"` / `"Khong"`; `null` khi FAILED |
| `modelName`, `modelVersion` | Mô hình đã dùng (`modelVersion` là mã băm trọng số) |
| `errorCode` | Chỉ có khi FAILED, xem bảng dưới |
| `experimental`, `disclaimer` | Luôn `true` và câu cảnh báo; FE phải hiển thị cùng kết quả |

Mọi lần chạy đều được lưu (bảng `predictions`, migration `V2__predictions.sql`), kể cả lần lỗi. `latestPrediction` là lần mới nhất ứng với ảnh **đang gắn**: gỡ ảnh thì về `null`, gắn ảnh khác thì chạy lại cho ảnh mới.

### Mã lỗi khi FAILED (`errorCode`)

| `errorCode` | Nguyên nhân |
|---|---|
| `PREDICT_UNAVAILABLE` | Không kết nối được service, hoặc service trả 5xx |
| `PREDICT_TIMEOUT` | Service không trả lời trong `PREDICT_TIMEOUT` |
| `PREDICT_IMAGE_REJECTED` | Service trả 4xx (ảnh không đọc được, sai định dạng) |
| `PREDICT_BAD_RESPONSE` | Phản hồi sai định dạng hoặc giá trị ngoài khoảng |
| `PREDICT_IMAGE_UNREADABLE` | Server không đọc được tệp ảnh đã lưu |

Các trường hợp này vẫn trả 201/200 như bình thường; FE đọc `latestPrediction.status`.

### Cấu hình

| Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|
| `PREDICT_ENABLED` | `true` | `false` thì không gọi mô hình; nút chạy lại trả 503 `PREDICT_DISABLED` |
| `PREDICT_BASE_URL` | `http://localhost:8001` | Địa chỉ service FastAPI (`predict/README.md`) |
| `PREDICT_TIMEOUT` | `30s` | Thời gian chờ phản hồi |
| `PREDICT_CONNECT_TIMEOUT` | `3s` | Thời gian chờ kết nối |

Server gọi service bằng HTTP/1.1 (uvicorn từ chối yêu cầu nâng cấp `h2c` với lỗi 400). Log chỉ ghi id, trạng thái, mã lỗi và thời gian; không ghi xác suất, nhãn hay nội dung ảnh.
