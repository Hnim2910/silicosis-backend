Dự án: silicosis-backend (Spring Boot 4.1.1, Java 21, Maven, Postgres, Flyway) tại `E:\Hoc hanh\silicosis-backend`. Trả lời tôi bằng tiếng Việt.

## Mục tiêu
Thêm endpoint upload ảnh X-quang ngực. Ảnh chỉ gắn với người dùng đã đăng nhập. Chưa làm hồ sơ bệnh nhân, lượt khám hay dự đoán.

## Đọc trước khi code
1. `Claude.md` ở gốc repo và `docs/api-auth.md` (quy ước, định dạng lỗi, cách viết tài liệu API).
2. `AuthController`, `UserController`, `SecurityConfig`, `common/ApiException`, `GlobalExceptionHandler`, `AppProperties`, `AuthFlowIntegrationTest`, `TestcontainersConfiguration` để làm theo đúng kiểu có sẵn (package theo tính năng, DTO là record, xoá mềm, kiểm thử bằng Testcontainers).
3. `src/main/resources/db/migration/V1__init_auth.sql` — bảng `images` đã được thêm sẵn ở cuối file này.

## Việc cần làm
1. **Không tạo migration mới.** Dự án chỉ có một file `V1__init_auth.sql`, bảng `images` đã nằm trong đó (cột: id, owner_id, storage_key, content_type, size_bytes, width, height, sha256, visit_id, deleted_at, created_at). Dùng đúng schema này; nếu thấy chỗ nào cần sửa thì nêu lý do trước, rồi sửa trực tiếp trong V1.
   Lưu ý: V1 đã bị sửa nên nếu database dev cũ đã chạy V1 thì Flyway sẽ báo sai checksum. Hướng dẫn tôi cách đặt lại database (`docker compose down -v` rồi `up -d`) thay vì tự xoá dữ liệu.
2. Package mới `com.sillicosis.backend.image`: entity, repository, service, controller, DTO.
3. Endpoint (tất cả cần đăng nhập):
   - `POST /api/images` — multipart, trường `file`. Trả 201 với metadata ảnh.
   - `GET /api/images` — danh sách ảnh của mình, có phân trang (dùng `PageResponse`).
   - `GET /api/images/{id}` — metadata.
   - `GET /api/images/{id}/content` — trả nội dung ảnh, đúng `Content-Type`, header `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`.
   - `DELETE /api/images/{id}` — xoá mềm trong DB và xoá file trên đĩa.
4. Quyền: chủ ảnh hoặc ADMIN. Người khác nhận 404, không phải 403, để không lộ ảnh có tồn tại.
5. Kiểm tra file:
   - Nhận PNG và JPEG; xác định loại bằng magic bytes, không tin Content-Type hay đuôi file do client gửi.
   - Giới hạn dung lượng 10 MB (cấu hình được); kích thước tối thiểu hợp lý (ví dụ cạnh ngắn ≥ 256 px) và tối đa (ví dụ ≤ 8000 px) để chặn ảnh bom giải nén. Giải mã thử bằng `ImageIO`; ảnh hỏng thì trả 400. Các con số này chỉ là mặc định, đặt thành cấu hình để tôi chỉnh theo ảnh thật.
   - Ghi lại ảnh sau khi giải mã để bỏ EXIF và mọi metadata, rồi tính `sha256` trên bản đã ghi lại.
   - Ảnh trùng (cùng chủ, cùng sha256) thì trả 409 kèm id ảnh cũ.
   - Mã lỗi mới theo đúng định dạng `ApiError` hiện có: `UNSUPPORTED_IMAGE_TYPE` (415), `IMAGE_TOO_LARGE` (413), `INVALID_IMAGE` (400), `IMAGE_DUPLICATE` (409), `IMAGE_NOT_FOUND` (404).
6. Lưu file ra đĩa: thư mục cấu hình qua `app.storage.images-dir` (thêm vào `AppProperties` và `.env.example`), tên file là UUID ngẫu nhiên, chống path traversal, không bao giờ dùng tên file của client. Nâng `spring.servlet.multipart.max-file-size` cho khớp giới hạn. Ghi file trước, rồi lưu DB; nếu lưu DB lỗi thì xoá file vừa ghi. Thư mục lưu ảnh phải nằm ngoài repo; thêm vào `.gitignore` nếu cần.
7. Quyền riêng tư: không log tên file gốc, nội dung ảnh hay sha256 ở mức INFO trở lên. Không trả đường dẫn lưu trữ ra API. Không lưu tên file gốc của client ở bất cứ đâu (nó có thể chứa họ tên bệnh nhân).
8. Kiểm thử: unit test cho phần kiểm tra file (PNG hợp lệ, file `.png` thực chất là text, ảnh quá lớn, ảnh hỏng, trùng) và integration test bằng Testcontainers (upload, tải về, quyền của người khác nhận 404, ADMIN xem được, xoá). Dùng thư mục tạm cho kiểm thử.
9. Viết `docs/api-images.md` cùng kiểu với `docs/api-auth.md`, và thêm mục tham chiếu vào README nếu có.

## Ràng buộc
- Không chạy git (không stage, không commit, không push); tôi tự làm.
- Không đưa ảnh hay tệp thật vào repo.
- Không thêm thư viện lớn khi dùng được JDK; nếu cần thêm thì nêu lý do.
- Chưa làm: hồ sơ bệnh nhân, lượt khám, dự đoán, gọi FastAPI.

## Cách kiểm tra
Chạy `./mvnw verify`. Rồi chạy thử bằng Swagger tại `/swagger-ui.html`: đăng ký, đăng nhập, upload một PNG, tải về, thử tài khoản khác tải ảnh đó. Báo cho tôi bước nào đã chạy thật, bước nào chưa.

Trước khi code, tóm tắt kế hoạch trong 10 dòng.
