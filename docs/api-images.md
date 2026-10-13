# SilicoCare API — Ảnh X-quang

Base URL dev: `http://localhost:8080` · Swagger UI: `/swagger-ui.html`

Tất cả endpoint trong tài liệu này cần access token:

```http
Authorization: Bearer <accessToken>
```

## Upload ảnh

### `POST /api/images` → 201 `ImageResponse`

Gửi `multipart/form-data` với trường `file`:

```bash
curl -X POST http://localhost:8080/api/images \
  -H "Authorization: Bearer <accessToken>" \
  -F "file=@chest-xray.png"
```

Ví dụ phản hồi:

```json
{
  "id": "d2388bce-65e1-47a6-ae03-c40cd8783a43",
  "contentType": "image/png",
  "sizeBytes": 34567,
  "width": 1600,
  "height": 1200,
  "createdAt": "2026-10-13T12:00:00Z"
}
```

Server chỉ chấp nhận PNG và JPEG, xác định định dạng từ magic bytes, giải mã ảnh và mã hóa lại trước khi lưu để loại bỏ metadata. Mặc định file tối đa 10 MB, cạnh ngắn tối thiểu 256 px và mỗi chiều tối đa 8000 px. Các giới hạn được cấu hình bằng `APP_IMAGE_MAX_UPLOAD_SIZE`, `APP_IMAGE_MIN_SHORT_SIDE` và `APP_IMAGE_MAX_DIMENSION`.

Ảnh trùng của cùng một tài khoản trả 409:

```json
{
  "timestamp": "2026-10-13T12:00:00Z",
  "status": 409,
  "code": "IMAGE_DUPLICATE",
  "message": "Ảnh này đã được tải lên.",
  "path": "/api/images",
  "details": { "imageId": "d2388bce-65e1-47a6-ae03-c40cd8783a43" }
}
```

## Danh sách và metadata

### `GET /api/images?page=0&size=20` → 200 `PageResponse<ImageResponse>`

Chỉ trả ảnh của tài khoản đang đăng nhập. `page` bắt đầu từ 0; `size` từ 1 đến 100.

### `GET /api/images/{id}` → 200 `ImageResponse`

Trả metadata ảnh. Chủ ảnh hoặc ADMIN được phép xem.

## Nội dung ảnh

### `GET /api/images/{id}/content` → 200 ảnh nhị phân

Trả đúng `Content-Type` (`image/png` hoặc `image/jpeg`), cùng các header:

```http
Cache-Control: private, no-store
X-Content-Type-Options: nosniff
```

Chủ ảnh hoặc ADMIN được phép tải ảnh. Tài khoản khác nhận 404 để không tiết lộ ảnh có tồn tại hay không.

## Xóa ảnh

### `DELETE /api/images/{id}` → 204

Chủ ảnh hoặc ADMIN có thể xóa. Bản ghi được xóa mềm trong database và file được xóa khỏi thư mục lưu trữ.

## Lỗi

| HTTP | `code` | Ý nghĩa |
|---|---|---|
| 400 | `INVALID_IMAGE` | Ảnh hỏng hoặc kích thước ảnh không được hỗ trợ |
| 401 | `UNAUTHORIZED` | Thiếu hoặc sai access token |
| 403 | `ACCOUNT_DISABLED` | Tài khoản đã bị khóa |
| 404 | `NOT_FOUND` | Không tìm thấy ảnh hoặc không có quyền xem ảnh |
| 409 | `IMAGE_DUPLICATE` | Ảnh trùng; `details.imageId` là ID ảnh đã có |
| 413 | `IMAGE_TOO_LARGE` | File ảnh vượt giới hạn |
| 415 | `UNSUPPORTED_IMAGE_TYPE` | Không phải PNG hoặc JPEG |

## Lưu trữ

File được lưu dưới tên UUID, không lưu tên file gốc từ client. Thư mục mặc định là `${user.home}/.silicocare/images`; đổi bằng `APP_STORAGE_IMAGES_DIR`. Nội dung file và SHA-256 không được trả trong response metadata.
