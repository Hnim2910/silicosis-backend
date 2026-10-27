Dự án: silicosis-backend (Spring Boot 4.1.1, Java 21, Maven, Postgres, Flyway) tại `E:\Hoc hanh\silicosis-backend`. Trả lời tôi bằng tiếng Việt.

## Mục tiêu
Thêm hồ sơ bệnh nhân: bảng `patients` và các endpoint đọc/ghi. Mỗi tài khoản USER có đúng một hồ sơ (tự tạo khi tài khoản được tạo). ADMIN có thể tạo và quản lý hồ sơ khác. Chưa làm lượt khám, dự đoán hay gắn ảnh vào hồ sơ.

## Đọc trước khi code
1. `Claude.md` ở gốc repo, `docs/api-auth.md`, `docs/api-images.md` (quy ước, định dạng lỗi, cách viết tài liệu API).
2. Package `user` (`UserService`, `UserController`, `AdminUserController`, `User`, `Gender`, `Role`), `auth/AuthService` (luồng register), `image` (làm mẫu cách tổ chức package, kiểm tra quyền chủ sở hữu và trả 404), `common/*`, `config/SecurityConfig`, `AuthFlowIntegrationTest`.
3. `src/main/resources/db/migration/V1__init_auth.sql` — bảng `patients` và sequence `patient_code_seq` đã được thêm sẵn ở cuối file.
4. Web (chỉ để đối chiếu tên trường, KHÔNG sửa): `E:\Hoc hanh\ĐÒ ÁN\sillicosis-web\src\types\patient.ts`, `components\patient\PatientForm.tsx`, `src\utils\validation.ts` (hàm `validatePatient`) và `src\services\patientService.ts` (mock: cho thấy quy tắc quyền và việc đồng bộ tên/ngày sinh/giới tính/nghề nghiệp giữa người dùng và hồ sơ).

## Quyết định thiết kế (đã chốt, nếu thấy bất hợp lý thì nêu lý do trước khi làm khác)
- **Mỗi tài khoản USER có đúng một hồ sơ.** Hồ sơ được tạo trong cùng một transaction với tài khoản, ở mọi nơi tạo tài khoản USER (đăng ký tự do, admin tạo user). Tìm hết các chỗ đó trong code trước khi làm. Tài khoản ADMIN không có hồ sơ.
- Hồ sơ mới của tài khoản lấy sẵn `full_name`, `birth_date`, `gender`, `occupation`, `phone` từ tài khoản (có thể null). Các trường còn lại để trống để người dùng điền sau.
- **Đồng bộ một chiều hai phía chỉ cho 4 trường:** họ tên, ngày sinh, giới tính, nghề nghiệp. Khi sửa `PUT /api/users/me` thì cập nhật hồ sơ gắn với tài khoản; khi sửa hồ sơ có `user_id` thì cập nhật tài khoản. `phone` và `email` KHÔNG đồng bộ (số điện thoại của tài khoản là duy nhất, của hồ sơ thì không).
- Hồ sơ do ADMIN tạo mà không gắn tài khoản có `user_id = null`.
- `code` (mã hiển thị) sinh từ `patient_code_seq`, định dạng `BN-` cộng 6 chữ số (ví dụ `BN-000001`). Sinh ở service, không để client gửi.

## Việc cần làm
1. **Không tạo migration mới.** Bảng `patients` đã nằm trong V1. Dùng đúng schema; nếu cần sửa thì nêu lý do trước rồi sửa trực tiếp trong V1. Vì V1 đã đổi, hướng dẫn tôi đặt lại database (`docker compose down -v` rồi `up -d`) thay vì tự xoá dữ liệu.
2. Package mới `com.sillicosis.backend.patient`: entity, repository, service, controller, DTO (record).
3. Endpoint (tất cả cần đăng nhập):
   - `GET /api/patients/me` — hồ sơ của chính mình (USER). ADMIN gọi thì trả 404 `PATIENT_NOT_FOUND`.
   - `GET /api/patients` — chỉ ADMIN, có phân trang (`PageResponse`) và tham số `q` tìm theo họ tên, mã, số điện thoại, nghề nghiệp (không phân biệt hoa thường, không phân biệt dấu nếu làm được đơn giản; nếu không thì bỏ phần dấu và báo tôi).
   - `POST /api/patients` — chỉ ADMIN, tạo hồ sơ không gắn tài khoản. Trả 201.
   - `GET /api/patients/{id}` — ADMIN hoặc chủ hồ sơ.
   - `PUT /api/patients/{id}` — ADMIN hoặc chủ hồ sơ. Không cho đổi `user_id`, `code`.
   - `DELETE /api/patients/{id}` — chỉ ADMIN, xoá mềm (`deleted_at`).
   - Người không có quyền nhận 404, không phải 403, để không lộ hồ sơ có tồn tại.
4. Trường trong JSON: `id`, `code`, `userId`, `fullName`, `birthDate`, `gender` (`MALE`/`FEMALE`/`OTHER`), `phone`, `occupation`, `workplace`, `exposureHistory`, `riskFactors`, `createdAt`, `updatedAt`. Ánh xạ từ các trường `name`, `birthDate`, `gender`, `phone`, `occupation`, `workplace`, `exposureHistory`, `riskFactors` của web. Lấy quy tắc kiểm tra (độ dài, ngày sinh không ở tương lai, định dạng SĐT…) từ `validatePatient` của web và `common/VnPhone`; backend là nơi kiểm tra cuối cùng.
5. Mã lỗi mới theo đúng định dạng `ApiError` hiện có: `PATIENT_NOT_FOUND` (404). Lỗi kiểm tra dữ liệu dùng `VALIDATION_FAILED` có sẵn.
6. Quyền riêng tư: không log họ tên, ngày sinh, số điện thoại, tiền sử phơi nhiễm ở mức INFO trở lên. Không đưa dữ liệu bệnh nhân thật vào seed hay test. Trường văn bản tự do (`exposure_history`, `risk_factors`) giới hạn độ dài hợp lý (ví dụ 2000 ký tự, cấu hình được).
7. Cập nhật Swagger/OpenAPI cho các endpoint mới.
8. Kiểm thử: unit test cho service (tạo hồ sơ khi đăng ký, đồng bộ 4 trường hai chiều, sinh mã) và integration test bằng Testcontainers: đăng ký xong có hồ sơ; USER xem/sửa hồ sơ của mình; USER xem hồ sơ người khác nhận 404; ADMIN tạo, liệt kê, tìm kiếm, sửa, xoá mềm; ADMIN tạo user USER thì cũng có hồ sơ; sửa `/users/me` thì hồ sơ đổi theo. Chạy lại toàn bộ test hiện có để chắc không hỏng luồng auth và image.
9. Viết `docs/api-patients.md` cùng kiểu với `docs/api-auth.md`, và thêm mục tham chiếu vào README nếu có.

## Ràng buộc
- Không chạy git (không stage, không commit, không push); tôi tự làm. Ràng buộc này ưu tiên hơn đoạn nói về commit trong `Claude.md`.
- Không sửa web hay app; chỉ viết tài liệu để tôi nối sau.
- Không thêm thư viện lớn khi dùng được JDK/Spring; nếu cần thêm thì nêu lý do.
- Chưa làm: lượt khám (visits), chỉ số lâm sàng, gắn ảnh vào hồ sơ, dự đoán, gọi FastAPI.

## Cách kiểm tra
Chạy `./mvnw verify`. Rồi thử bằng Swagger tại `/swagger-ui.html`: đăng ký tài khoản mới, gọi `GET /api/patients/me`, sửa hồ sơ, đổi họ tên ở `/api/users/me` và xem hồ sơ có đổi theo; đăng nhập admin, tạo hồ sơ, tìm kiếm, xoá mềm. Báo cho tôi bước nào đã chạy thật, bước nào chưa.

Trước khi code, tóm tắt kế hoạch trong 10 dòng.
