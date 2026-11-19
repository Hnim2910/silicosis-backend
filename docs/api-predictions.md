# SilicoCare API — Lượt chẩn đoán

Base URL dev: `http://localhost:8080` · Swagger UI: `/swagger-ui.html` (nhóm **Lượt chẩn đoán**) · OpenAPI JSON: `/v3/api-docs`

Mọi endpoint đều cần đăng nhập (`Authorization: Bearer <accessToken>`). Định dạng lỗi, token, vai trò và hồ sơ: xem [api-auth.md](api-auth.md).

## 1. Khái niệm

**Một lượt chẩn đoán** = một lần người dùng gửi thông tin lâm sàng và/hoặc một phim X-quang. Mỗi lượt gồm:
- đầu vào: ngày khám, `lamSang`, ảnh (nếu có);
- kết quả của model New Way.

Tất cả nằm trên **một dòng** của bảng `predictions`.

- **Không sửa được.** Đổi bất kỳ thông tin nào thì tạo lượt mới.
- **Model chạy ngay khi gửi.** Service AI lỗi thì lượt vẫn được lưu với `status = FAILED` và `errorCode`. Chỉ lượt `FAILED` mới được **thử lại**, và kết quả mới ghi đè ngay trên lượt đó.
- **Quyền:** chủ lượt chẩn đoán hoặc `ADMIN`. Người khác nhận **404** như khi không tồn tại.
- Đây là công cụ **sàng lọc thử nghiệm, không phải chẩn đoán**. Không hiển thị chữ "dương tính" / "âm tính".

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

Request là `multipart/form-data`, gồm hai phần, **cả hai đều không bắt buộc**, nhưng phải có ít nhất ảnh hoặc một giá trị lâm sàng:

| Phần | Kiểu | Nội dung |
|---|---|---|
| `data` | `application/json` | `{"examDate": "2026-11-01", "lamSang": {...}}` |
| `file` | `image/png` hoặc `image/jpeg` | Phim X-quang ngực thẳng |

- `examDate`: để trống thì lấy ngày gửi. Không được ở tương lai, không được trước ngày sinh trong hồ sơ.
- `lamSang`: object phẳng `{tên_cột: giá_trị}`. Tên cột và mã hợp lệ theo [predict/ml/new_way/MO_TA_API.md](../predict/ml/new_way/MO_TA_API.md).
  - Số có thể gửi dạng số hoặc chuỗi có dấu phẩy, ví dụ `"3,5"`.
  - Cột danh mục dùng **mã không dấu**, ví dụ `"Co"`, `"Khong"`, `"Co hut thuoc"`.
  - `null` hoặc `""` nghĩa là bỏ trống.
  - Tối đa 200 cột và 32 KB (`APP_PREDICTION_LAM_SANG_MAX_SIZE`).
- **Năm sinh lấy từ hồ sơ.** Nếu hồ sơ có `birthDate` thì backend ghi `namsinh` = năm sinh, ghi đè giá trị client gửi.
- **Giới tính:** giữ giá trị `gioitinh` client gửi (người dùng có thể chọn "chỉ dùng cho lần này"). Để trống thì backend lấy từ hồ sơ: `MALE`/`FEMALE` → `"Nam"`/`"Nu"`.
- Tiểu sử (`tieuSu` của hồ sơ) **không** được backend tự chép; web/app điền sẵn vào form rồi gửi trong `lamSang`. Mỗi lượt lưu nguyên `lamSang` của lúc gửi, nên sửa hồ sơ về sau không làm đổi lượt cũ.
- **Ảnh:**
  - Định dạng xét theo nội dung tệp, không theo tên.
  - Tối đa 10 MB, cạnh ngắn ≥ 256 px, mỗi chiều ≤ 8000 px.
  - Ảnh được giải mã rồi mã hoá lại để xoá EXIF, lưu ở thư mục `APP_STORAGE_IMAGES_DIR` với tên tệp ngẫu nhiên.
  - Gửi lại cùng một phim cho lượt khác vẫn được.

Ví dụ với `curl`:

```bash
curl -X POST http://localhost:8080/api/predictions \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data={"examDate":"2026-11-01","lamSang":{"ho":"Co","tdho":"Ban dem","fvc":"72,5"}};type=application/json' \
  -F 'file=@phim.png;type=image/png'
```

Ví dụ với JavaScript (web):

```js
const form = new FormData();
form.append("data", new Blob([JSON.stringify({ examDate, lamSang })], { type: "application/json" }));
if (file) form.append("file", file);
fetch("/api/predictions", { method: "POST", headers: { Authorization: `Bearer ${token}` }, body: form });
```

Thời gian phản hồi phụ thuộc service AI: thường vài giây, tối đa khoảng `PREDICT_TIMEOUT` (30 giây).

## 4. `PredictionResponse`

```json
{
  "id": "0c7f1f7e-2f0b-4a8e-9a55-3c1e0d6c1b11",
  "user": { "id": "5bb190d9-…", "code": "BN-000012", "fullName": "Nguyễn Văn A" },
  "examDate": "2026-11-01",
  "lamSang": { "ho": "Co", "tdho": "Ban dem", "fvc": "72,5", "gioitinh": "Nam", "namsinh": 1975 },
  "image": { "contentType": "image/png", "sizeBytes": 812345, "width": 2048, "height": 2048 },
  "status": "SUCCESS",
  "errorCode": null,
  "pLamSang": 0.2,
  "nguongLamSang": 0.051546,
  "baoNguyCoLamSang": true,
  "coAnh": true,
  "pAnh": 0.6,
  "pGhep": 0.55,
  "nguongGhep": 0.207134,
  "baoNguyCoGhep": true,
  "ketLuanCuoi": true,
  "modelName": "New Way ketqua",
  "modelVersion": "68b803152d39",
  "createdAt": "2026-11-18T03:00:00Z",
  "updatedAt": "2026-11-18T03:00:00Z",
  "experimental": true,
  "disclaimer": "Công cụ hỗ trợ sàng lọc thử nghiệm, không phải chẩn đoán; không thay thế đánh giá của bác sĩ."
}
```

- `image` là `null` khi không gửi ảnh. Khi đó `coAnh` = `false`, còn `pAnh`, `pGhep`, `nguongGhep`, `baoNguyCoGhep` = `null`.
- `ketLuanCuoi`: có ảnh thì theo `pGhep ≥ nguongGhep`, không có ảnh thì theo `pLamSang ≥ nguongLamSang`.
- `status = FAILED` thì chỉ có `errorCode`; mọi điểm và kết luận đều là `null`.
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
| 400 | `EMPTY_SUBMISSION` | Không có ảnh lẫn giá trị lâm sàng nào |
| 400 | `VALIDATION_FAILED` | `fieldErrors` theo khoá `examDate`, `lamSang` hoặc `lamSang.<cột>` (cột không có trong model, mã danh mục sai, không phải số, chữ > 500 ký tự…) |
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
| `PREDICT_UNAVAILABLE` | Không kết nối được service AI, hoặc service trả lỗi 5xx (VD chưa nạp model) |
| `PREDICT_TIMEOUT` | Quá `PREDICT_TIMEOUT` |
| `PREDICT_BAD_RESPONSE` | Service trả dữ liệu không hợp lệ hoặc thiếu `X-Model-Version` |
| `PREDICT_IMAGE_REJECTED` | Service từ chối ảnh (lỗi 4xx) |
| `PREDICT_INVALID_INPUT` | Service báo lâm sàng sai (422); xảy ra khi backend chưa lấy được `/schema` lúc lưu |
| `PREDICT_IMAGE_UNREADABLE` | Không đọc lại được tệp ảnh đã lưu khi thử lại |
| `PREDICT_DISABLED` | `PREDICT_ENABLED=false` lúc gửi |

## 7. Riêng tư

- Log chỉ ghi id lượt, trạng thái, mã lỗi và thời gian xử lý. Log không ghi điểm, kết luận, giá trị lâm sàng hay nội dung ảnh.
- Ảnh được xoá EXIF trước khi lưu. Xoá lượt chẩn đoán là xoá mềm, nên tệp ảnh vẫn được giữ cùng bản ghi.
- `GET /{id}/image` trả header `Cache-Control: private, no-store`.
