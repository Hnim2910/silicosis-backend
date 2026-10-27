Dự án: silicosis-backend (Spring Boot 4.1.1, Java 21, Maven, Postgres, Flyway) tại `E:\Hoc hanh\silicosis-backend`. Trả lời tôi bằng tiếng Việt.

## Mục tiêu
Thêm lượt khám (visits) vào hồ sơ bệnh nhân và cho phép gắn đúng một ảnh X-quang vào mỗi lượt khám. **Đây là bản demo**: bộ trường sẽ được chỉnh lại sau khi có từ điển trường chính thức, nên hãy làm đơn giản, dễ sửa, đừng chi tiết hoá quá mức. Chưa làm dự đoán hay gọi FastAPI.

## Đọc trước khi code
1. `Claude.md` ở gốc repo, `docs/api-auth.md`, `docs/api-images.md`, `docs/api-patients.md`.
2. Package `patient` (làm mẫu: entity, service, controller, DTO, kiểm tra quyền chủ hồ sơ và trả 404, xoá mềm) và package `image` (làm mẫu kiểm tra quyền chủ ảnh).
3. `src/main/resources/db/migration/V1__init_auth.sql` — bảng `visits` đã được thêm sẵn ở cuối file, cùng khoá ngoại `images.visit_id → visits.id`.

## Quyết định thiết kế (đã chốt)
- Một hồ sơ bệnh nhân có nhiều lượt khám. Một lượt khám có tối đa **một** ảnh (chỉ mục unique `ux_images_visit` đã có).
- **Tuổi và giới tính không lưu trong lượt khám.** Chúng lấy từ hồ sơ bệnh nhân. Trong response của lượt khám trả thêm `ageAtVisit` (tính từ `visitDate` và ngày sinh của hồ sơ, null nếu hồ sơ chưa có ngày sinh) và `bmi` (tính từ chiều cao, cân nặng, null nếu thiếu). Cả hai chỉ tính khi trả về, không lưu DB.
- **Quyền:** chủ hồ sơ (USER) và ADMIN đều được tạo, xem, sửa, xoá lượt khám của hồ sơ đó. Người khác nhận 404 (không phải 403). Ghi chú trong tài liệu: bản mock của web từng không cho ADMIN sửa dữ liệu lâm sàng; bản demo này cho phép để dễ thử, sẽ xem lại sau.
- Mọi trường đều tuỳ chọn (một lần gửi có thể chỉ có ảnh). `visitDate` trống = ngày gửi (không ở tương lai); `createdAt` = thời điểm gửi. Khoảng giá trị hợp lệ lấy theo ràng buộc CHECK trong SQL; kiểm tra ở tầng validation để trả lỗi tiếng Việt rõ ràng, trước khi chạm DB.
- Quy tắc liên trường: `systolicBp` > `diastolicBp` nếu cả hai có; `fev1L` ≤ `fvcL` nếu cả hai có; `cigarettesPerDay` chỉ có ý nghĩa khi `smoker = true` (nếu `smoker = false` thì bỏ qua và lưu null).
- **Giới tính / ngày sinh:** không chặn khi lưu; sẽ kiểm tra đủ (cùng `industry`, `yearsInJob`, `province`) ở bước dự đoán sau này. Tạo lượt khám không có ảnh, chỉ số nào và `extra` trống → 400 `EMPTY_SUBMISSION`.
- `industry` và `province` lưu dưới dạng chuỗi, **chưa** ràng buộc danh sách giá trị (sẽ có bảng ánh xạ chính thức sau). Không tự chuẩn hoá tiếng Việt thành không dấu.
- `extra` là một đối tượng JSON tự do (đồ bảo hộ, hợp đồng, thể lực, triệu chứng, tiền sử…). Chỉ kiểm tra: phải là object, kích thước tối đa 8 KB (cấu hình được), không cho giá trị lồng quá sâu (ví dụ tối đa 3 cấp). `schemaVersion` mặc định 1, server quản lý, client không gửi.

## Việc cần làm
1. **Không tạo migration mới.** Bảng `visits` đã nằm trong V1. Dùng đúng schema; nếu cần sửa thì nêu lý do trước rồi sửa trực tiếp trong V1. Vì V1 đã đổi, hướng dẫn tôi đặt lại database (`docker compose down -v` rồi `up -d`).
2. Package mới `com.sillicosis.backend.visit`: entity (ánh xạ JSONB sang `Map<String,Object>` hoặc kiểu phù hợp với Hibernate trong Spring Boot 4), repository, service, controller, DTO (record).
3. Endpoint (tất cả cần đăng nhập):
   - `POST /api/patients/{patientId}/visits` — tạo lượt khám. Trả 201.
   - `GET /api/patients/{patientId}/visits` — danh sách có phân trang, mới nhất trước.
   - `GET /api/visits/{id}` — chi tiết, kèm thông tin ảnh đã gắn (id ảnh, kích thước, loại) hoặc null.
   - `PUT /api/visits/{id}` — ghi đè toàn bộ trường sửa được (trường không gửi thành null; `visitDate` không gửi thì giữ nguyên). Không đổi `patientId`.
   - `DELETE /api/visits/{id}` — xoá mềm lượt khám; ảnh đang gắn (nếu có) được **gỡ khỏi lượt khám** (`visit_id = null`), không xoá ảnh.
   - `PUT /api/visits/{id}/image` — body `{ "imageId": "…" }`, gắn ảnh vào lượt khám. Ảnh phải tồn tại, chưa bị xoá, thuộc người đang thao tác (hoặc ADMIN), và chưa gắn với lượt khám khác. Nếu lượt khám đã có ảnh khác thì trả 409 `VISIT_ALREADY_HAS_IMAGE` (muốn đổi thì gỡ ảnh cũ trước).
   - `DELETE /api/visits/{id}/image` — gỡ ảnh khỏi lượt khám (không xoá ảnh).
4. Cập nhật `ImageController`/response ảnh: trả thêm `visitId` (null nếu chưa gắn). Cập nhật `DELETE /api/images/{id}`: ảnh đang gắn với lượt khám thì vẫn xoá được, nhưng phải gỡ liên kết trong cùng transaction.
5. Mã lỗi mới theo đúng định dạng `ApiError` hiện có: `EMPTY_SUBMISSION` (400), `VISIT_NOT_FOUND` (404), `VISIT_ALREADY_HAS_IMAGE` (409), `IMAGE_ALREADY_ATTACHED` (409, ảnh đã gắn vào lượt khám khác). Lỗi kiểm tra dữ liệu dùng `VALIDATION_FAILED` có sẵn, trả theo từng trường.
6. Quyền riêng tư: không log giá trị chỉ số lâm sàng hay nội dung `extra` ở mức INFO trở lên. `toString()` của request không in dữ liệu (làm giống `PatientRequest`). Không đưa dữ liệu bệnh nhân thật vào seed hay test.
7. Kiểm thử: unit test cho service (tuổi và BMI tính đúng, quy tắc liên trường, gắn/gỡ ảnh, ảnh đã gắn chỗ khác) và integration test bằng Testcontainers: USER tạo/sửa/xoá lượt khám của mình; USER thao tác trên hồ sơ người khác nhận 404; ADMIN thao tác được; gắn ảnh thành công, gắn ảnh thứ hai vào cùng lượt khám bị 409, gắn một ảnh vào hai lượt khám bị 409; xoá lượt khám thì ảnh được gỡ liên kết. Chạy lại toàn bộ test hiện có để chắc không hỏng auth, image, patient.
8. Viết `docs/api-visits.md` cùng kiểu với `docs/api-patients.md`. Thêm một mục "Bản demo, sẽ chỉnh lại" liệt kê rõ những chỗ tạm: danh sách `industry`/`province` chưa ràng buộc; `systolicBp`/`diastolicBp` chưa xác nhận với từ điển nguồn; `extra` chưa có schema; quyền ADMIN khác mock.

## Ràng buộc
- Không chạy git (không stage, không commit, không push); tôi tự làm. Ràng buộc này ưu tiên hơn đoạn nói về commit trong `Claude.md`.
- Không sửa web hay app; chỉ viết tài liệu để tôi nối sau.
- Không thêm thư viện lớn khi dùng được JDK/Spring; nếu cần thêm thì nêu lý do.
- Chưa làm: dự đoán, gọi FastAPI, danh mục ngành/tỉnh, bảng ánh xạ sang nhãn mô hình.

## Cách kiểm tra
Chạy `./mvnw verify`. Rồi thử bằng Swagger tại `/swagger-ui.html`: đăng ký, đăng nhập, `GET /api/patients/me`, sửa hồ sơ cho có ngày sinh, tạo lượt khám, upload ảnh, gắn ảnh vào lượt khám, thử gắn ảnh thứ hai (phải 409), đăng nhập tài khoản khác thử đọc lượt khám (phải 404), xoá lượt khám rồi kiểm tra ảnh còn nguyên. Báo cho tôi bước nào đã chạy thật, bước nào chưa.

Trước khi code, tóm tắt kế hoạch trong 10 dòng.
