# SilicoCare API — Lượt chẩn đoán

Base URL dev: `http://localhost:8080` · Swagger UI: `/swagger-ui.html` (nhóm **Lượt chẩn đoán**) · OpenAPI JSON: `/v3/api-docs`

Mọi endpoint đều cần đăng nhập (`Authorization: Bearer <accessToken>`). Định dạng lỗi, token, vai trò và hồ sơ: xem [api-auth.md](api-auth.md).

## 1. Khái niệm

**Một lượt chẩn đoán** = một lần người dùng gửi thông tin lâm sàng, có thể kèm một phim X-quang. Mỗi lượt gồm:
- đầu vào: ngày khám, `lamSang` (bắt buộc có `tinh`), ảnh (nếu có);
- kết quả của **mô hình PG** (nhãn `ketqua` = phim được đọc là nghi bụi phổi silic), xem
  [predict/ml/pg/MO_TA_API.md](../predict/ml/pg/MO_TA_API.md).

Tất cả nằm trên **một dòng** của bảng `predictions`.

- **Không sửa được.** Đổi bất kỳ thông tin nào thì tạo lượt mới.
- **Model chạy ngay khi gửi.** Service AI lỗi thì lượt vẫn được lưu với `status = FAILED` và `errorCode`. Chỉ lượt `FAILED` mới được **thử lại**, và kết quả mới ghi đè ngay trên lượt đó.
- **Quyền:** chủ lượt chẩn đoán hoặc `ADMIN`. Người khác nhận **404** như khi không tồn tại.
- Đây là công cụ **sàng lọc thử nghiệm, không phải chẩn đoán**.
- **PG chỉ trả xác suất đã hiệu chỉnh.** Chưa có ngưỡng được duyệt, nên API **không có ngưỡng và không có kết luận** dương/âm hay "có nguy cơ/không". Web chỉ hiển thị xác suất kèm cảnh báo, không tự đặt ngưỡng.

## 2. Endpoint

| Method | Path | Mô tả | Thành công |
|---|---|---|---|
| POST | `/api/predictions` | Gửi một lượt chẩn đoán (multipart) | 201 `PredictionResponse` + header `Location` |
| GET | `/api/predictions?page=0&size=20` | Lượt chẩn đoán của tôi, mới nhất trước | 200 `PageResponse<PredictionResponse>` |
| GET | `/api/predictions/all?page=0&size=20` | Mọi lượt chẩn đoán (**chỉ ADMIN**) | 200 `PageResponse<PredictionResponse>` |
| GET | `/api/predictions/{id}` | Chi tiết | 200 `PredictionResponse` |
| GET | `/api/predictions/{id}/image` | Nội dung ảnh X-quang | 200 `image/png` hoặc `image/jpeg` |
| POST | `/api/predictions/{id}/retry` | Chạy lại model cho lượt `FAILED` | 200 `PredictionResponse` |
| DELETE | `/api/predictions/{id}` | Xoá mềm | 204 |

Danh sách sắp theo `examDate` rồi `createdAt`, giảm dần. `size` nhận giá trị 1–100.

## 3. `POST /api/predictions`

Request là `multipart/form-data`, gồm hai phần:

| Phần | Kiểu | Nội dung |
|---|---|---|
| `data` | `application/json` | **Bắt buộc** (vì phải có `lamSang.tinh`): `{"examDate": "2026-11-01", "lamSang": {...}}` |
| `file` | `image/png` hoặc `image/jpeg` | Phim X-quang ngực thẳng, không bắt buộc |

Ngoài `tinh`, phải có ảnh hoặc ít nhất một giá trị lâm sàng khác. Nếu không, request bị từ chối với `EMPTY_SUBMISSION`.

- `examDate`: để trống thì lấy ngày gửi. Không được ở tương lai, không được trước ngày sinh trong hồ sơ.
- `lamSang.tinh` **bắt buộc**, phải là đúng một trong 5 mã không dấu sau (đây là 5 tỉnh có trong dữ liệu học):

  | Gửi | Hiển thị |
  |---|---|
  | `Binh Dinh` | Bình Định |
  | `Dong Nai` | Đồng Nai |
  | `Hai Duong` | Hải Dương |
  | `Phu Yen` | Phú Yên |
  | `Thai Nguyen` | Thái Nguyên |

  Thiếu tỉnh, gửi tỉnh khác, gửi bản có dấu hoặc sai hoa/thường → 400 `VALIDATION_FAILED`, `fieldErrors['lamSang.tinh']`. Web nên dùng ô chọn.
- `lamSang`: object phẳng `{tên_cột: giá_trị}`. Tên cột và mã danh mục theo [predict/ml/pg/MO_TA_API.md](../predict/ml/pg/MO_TA_API.md) mục 4. Backend kiểm theo `GET /schema` của service AI:
  - **Cột không có trong mô hình PG bị từ chối.** Ví dụ các cột cũ của New Way như `fvc`, `fev1`, `gaenler` → lỗi "Trường không có trong mô hình.".
  - **Cột do mô hình tự tính bị từ chối:** `bmi`, `fev1_fvc`, `cviec_lv`, `pxuong_lv`, `cviec1_lv`, `cviec2_lv`.
  - Số có thể gửi dạng số hoặc chuỗi có dấu phẩy, ví dụ `"3,5"`.
  - Cột danh mục phải là **đúng một mức** mô hình đã học, viết không dấu, ví dụ `"Co"`, `"Khong"`. Số `1`, chuỗi `"1"` và `"1.0"` đều khớp mức `"1"`.
  - Cột chữ tự do dài tối đa 500 ký tự.
  - `null` hoặc `""` nghĩa là bỏ trống. Không có cột nào bắt buộc ngoài `tinh`.
  - Tối đa 200 cột và 32 KB (`APP_PREDICTION_LAM_SANG_MAX_SIZE`).
  - Nếu service AI chưa chạy lúc gửi, backend chỉ kiểm được `tinh`. Khi đó service AI vẫn kiểm lại lúc dự đoán; nếu sai thì lượt bị `FAILED` với `PREDICT_INVALID_INPUT`.
- **Năm sinh lấy từ hồ sơ.** Nếu hồ sơ có `birthDate` thì backend ghi `namsinh` = năm sinh, ghi đè giá trị client gửi.
- **Giới tính:** giữ giá trị `gioitinh` client gửi (người dùng có thể chọn "chỉ dùng cho lần này"). Để trống thì backend lấy từ hồ sơ: `MALE`/`FEMALE` → `"Nam"`/`"Nu"`.
- Tiểu sử (`tieuSu` của hồ sơ) **không** được backend tự chép; web/app điền sẵn vào form rồi gửi trong `lamSang`. Mỗi lượt lưu nguyên `lamSang` của lúc gửi, nên sửa hồ sơ về sau không làm đổi lượt cũ.
- **Ảnh:**
  - Định dạng xét theo nội dung tệp, không theo tên.
  - Tối đa 10 MB, cạnh ngắn ≥ 256 px, mỗi chiều ≤ 8000 px.
  - Ảnh được giải mã rồi mã hoá lại để xoá EXIF, lưu ở thư mục `APP_STORAGE_IMAGES_DIR` với tên tệp ngẫu nhiên.
  - Gửi lại cùng một phim cho lượt khác vẫn được.

Ví dụ với `curl` (dữ liệu giả):

```bash
curl -X POST http://localhost:8080/api/predictions \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data={"examDate":"2026-11-01","lamSang":{"tinh":"Thai Nguyen","ho":"Co","fvctt":"3,2"}};type=application/json' \
  -F 'file=@phim.png;type=image/png'
```

Ví dụ với JavaScript (web):

```js
const form = new FormData();
form.append("data", new Blob([JSON.stringify({ examDate, lamSang })], { type: "application/json" }));
if (file) form.append("file", file);
fetch("/api/predictions", { method: "POST", headers: { Authorization: `Bearer ${token}` }, body: form });
```

Thời gian phản hồi phụ thuộc service AI: thường vài giây, tối đa khoảng `PREDICT_TIMEOUT` (30 giây). Lần gửi ảnh đầu tiên sau khi khởi động có thể lâu hơn nếu service không nạp sẵn phần xử lý ảnh.

## 4. `PredictionResponse`

Ví dụ dưới đây dùng dữ liệu giả:

```json
{
  "id": "0c7f1f7e-2f0b-4a8e-9a55-3c1e0d6c1b11",
  "user": { "id": "5bb190d9-…", "code": "BN-000012", "fullName": "Nguyễn Văn A" },
  "examDate": "2026-11-01",
  "lamSang": { "tinh": "Thai Nguyen", "ho": "Co", "fvctt": "3,2", "gioitinh": "Nam", "namsinh": 1975 },
  "image": { "contentType": "image/png", "sizeBytes": 812345, "width": 2048, "height": 2048 },
  "status": "SUCCESS",
  "errorCode": null,
  "xacSuat": 0.55,
  "xacSuatTheoFold": [0.5, 0.6, 0.55, 0.52, 0.58],
  "coAnh": true,
  "chuYVung": { "RU": 0.2, "RM": 0.15, "RL": 0.1, "LU": 0.25, "LM": 0.18, "LL": 0.12 },
  "canhBao": ["…câu cảnh báo tiếng Việt do mô hình sinh…"],
  "modelName": "PG ketqua",
  "modelVersion": "68b803152d39",
  "createdAt": "2026-11-18T03:00:00Z",
  "updatedAt": "2026-11-18T03:00:00Z",
  "experimental": true,
  "disclaimer": "Công cụ hỗ trợ sàng lọc thử nghiệm, không phải chẩn đoán; không thay thế đánh giá của bác sĩ."
}
```

| Trường | Ý nghĩa |
|---|---|
| `xacSuat` | Xác suất `ketqua` = 1, từ 0 đến 1, đã hiệu chỉnh (Platt: chỉnh để con số gần với tỉ lệ thực tế), là trung bình các mô hình fold |
| `xacSuatTheoFold` | Xác suất của từng mô hình fold (mỗi mô hình học trên một phần dữ liệu khác nhau). Độ chênh giữa chúng cho thấy kết quả dao động bao nhiêu |
| `coAnh` | Lượt này có dùng ảnh hay không. Luôn bằng `image != null` |
| `chuYVung` | Chỉ có khi có ảnh: mức chú ý của mô hình lên 6 vùng phổi `RU RM RL LU LM LL` (phải/trái × trên/giữa/dưới), tổng = 1. **Chỉ để tham khảo, không phải bản đồ tổn thương.** Không có ảnh thì là `null` |
| `canhBao` | Danh sách câu tiếng Việt nên hiện cho người dùng, ví dụ về chất lượng ảnh hoặc về tỉnh ít dữ liệu. Có thể rỗng |

- **Không có** ngưỡng hay kết luận. Các trường cũ `pLamSang`, `nguongLamSang`, `baoNguyCoLamSang`, `pAnh`, `pGhep`, `nguongGhep`, `baoNguyCoGhep`, `ketLuanCuoi` đã bỏ (migration V7).
- `status = FAILED` thì chỉ có `errorCode`; `xacSuat`, `xacSuatTheoFold`, `chuYVung`, `canhBao` đều là `null`.
- Trường có giá trị `null` có thể không xuất hiện trong JSON.

## 5. Thử lại: `POST /api/predictions/{id}/retry`

- Chạy lại model với **đúng** `lamSang` và ảnh đã lưu của lượt đó.
- Kết quả, kể cả khi vẫn lỗi, ghi đè trên chính lượt đó (`updatedAt` đổi). Không tạo lượt mới.
- Lượt đã `SUCCESS` → 409 `PREDICTION_NOT_FAILED`.
- Tắt dự đoán (`PREDICT_ENABLED=false`) → 503 `PREDICT_DISABLED`.

## 6. Mã lỗi

**Lỗi của request** (không tạo lượt nào):

| HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `EMPTY_SUBMISSION` | Không có ảnh và ngoài `tinh` không có giá trị lâm sàng nào |
| 400 | `VALIDATION_FAILED` | `fieldErrors` theo khoá `examDate`, `lamSang` hoặc `lamSang.<cột>`: thiếu/sai `tinh`, cột không có trong mô hình PG, cột mô hình tự tính, mã danh mục sai, không phải số, chữ > 500 ký tự… |
| 400 | `INVALID_IMAGE` | Ảnh hỏng hoặc kích thước không hợp lệ |
| 400 | `BAD_REQUEST` | Phần `data` không phải JSON hợp lệ |
| 403 | `ACCOUNT_DISABLED` | Tài khoản bị khoá |
| 404 | `PREDICTION_NOT_FOUND` | Không có, đã xoá, hoặc không phải của bạn |
| 409 | `PREDICTION_NOT_FAILED` | Thử lại một lượt đã thành công |
| 413 | `IMAGE_TOO_LARGE` | Ảnh vượt dung lượng |
| 415 | `UNSUPPORTED_IMAGE_TYPE` | Ảnh không phải PNG/JPEG |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Không gửi multipart, hoặc phần `data` không phải `application/json` |
| 503 | `PREDICT_DISABLED` | Thử lại khi chức năng dự đoán đang tắt |

**Lỗi của service AI** (lượt vẫn được lưu, HTTP 201 hoặc 200, `status = FAILED`):

| `errorCode` | Ý nghĩa |
|---|---|
| `PREDICT_UNAVAILABLE` | Không kết nối được service AI, hoặc service trả lỗi 5xx (VD 503 `MODEL_NOT_LOADED`: chưa nạp mô hình PG) |
| `PREDICT_TIMEOUT` | Quá `PREDICT_TIMEOUT` |
| `PREDICT_BAD_RESPONSE` | Service trả dữ liệu không hợp lệ (xác suất ngoài 0–1, `coAnh` không khớp việc có gửi ảnh…) hoặc thiếu `X-Model-Version` |
| `PREDICT_IMAGE_REJECTED` | Service từ chối ảnh (lỗi 4xx khác 422) |
| `PREDICT_INVALID_INPUT` | Service báo lâm sàng sai (422); xảy ra khi backend chưa lấy được `/schema` lúc lưu |
| `PREDICT_IMAGE_UNREADABLE` | Không đọc lại được tệp ảnh đã lưu khi thử lại |
| `PREDICT_DISABLED` | `PREDICT_ENABLED=false` lúc gửi |

## 7. Riêng tư

- Log chỉ ghi id lượt, trạng thái, mã lỗi và thời gian xử lý. Log không ghi xác suất, giá trị lâm sàng hay nội dung ảnh.
- Ảnh được xoá EXIF trước khi lưu. Xoá lượt chẩn đoán là xoá mềm, nên tệp ảnh vẫn được giữ cùng bản ghi.
- `GET /{id}/image` trả header `Cache-Control: private, no-store`.
- Migration V7 đã xoá mọi lượt chẩn đoán cũ (dữ liệu thử của New Way). Tệp ảnh cũ trên đĩa không bị xoá tự động.
