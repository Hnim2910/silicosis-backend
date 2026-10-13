
Dự án: SilicoCare — hệ thống hỗ trợ sàng lọc bệnh bụi phổi silic (đồ án tốt nghiệp). Trả lời tôi bằng tiếng Việt.

## Bối cảnh

- Backend: `E:\Hoc hanh\silicosis-backend` — Spring Boot **4.1.1**, Java 21, Maven, đã có starter webmvc, data-jpa, validation, postgresql, lombok, devtools. Hiện chỉ có `BackendApplication.java` và `application.yaml` (chỉ có tên app). Package gốc `com.sillicosis.backend`.
- Frontend web: `E:\Hoc hanh\ĐÒ ÁN\sillicosis-web` (Next.js). Có `src/types/user.ts` (Role = patient | doctor | technician | admin), `src/services/authService.ts`, `src/app/register/page.tsx`. Mock chỉ là tượng trưng, không cần bám theo dữ liệu mock; chỉ đọc để biết các trường form và vai trò.
- App React Native: `E:\Training\silicosis-mobile`. Sau này dùng chung API này.
- Phần model AI sẽ là một service FastAPI riêng, làm sau. **Không** đụng tới trong phiên này.
- Là Spring Boot 4 nên kiểm tra API mới (Spring Security 7, Jakarta EE 11); đừng dùng cú pháp đã bị bỏ như `WebSecurityConfigurerAdapter`.

## Việc cần làm (giai đoạn 1: xác thực)

1. **Kết nối PostgreSQL**
   - Cấu hình trong `application.yaml`, mọi thông tin bí mật đọc từ biến môi trường (`DB_URL`, `DB_USER`, `DB_PASSWORD`).
   - Có `.env.example`; thêm `.env` vào `.gitignore`.
   - Quản lý schema bằng Flyway (không dùng `ddl-auto=update`).
   - Kèm `docker-compose.yml` chạy Postgres để dev.
2. **Bảng `users`**
   - Các cột: id (UUID), email (unique), phone (unique, tuỳ chọn), password_hash (BCrypt), full_name, role (enum 2 vai trò: USER = bệnh nhân, ADMIN), enabled, created_at, updated_at.
   - Đăng nhập bằng email **hoặc** số điện thoại.
3. **Register**
   - Tự đăng ký luôn ra vai trò USER (bệnh nhân).
   - Không có vai trò bác sĩ/kỹ thuật viên; tài khoản admin do admin tạo.
   - Seed sẵn 1 tài khoản admin qua biến môi trường.
   - Validate email, số điện thoại VN, mật khẩu (≥ 8 ký tự, có chữ và số).
4. **Login**
   - Spring Security, stateless, JWT: access token khoảng 15 phút và refresh token khoảng 7 ngày.
   - Refresh token lưu DB dạng hash để thu hồi được. Có `/refresh` và `/logout`.
   - Khoá tạm thời sau 5 lần sai liên tiếp.
5. **Quên mật khẩu bằng OTP qua SMTP**
   - Luồng gồm 3 bước:
     - `POST /forgot-password` (nhập email): sinh OTP 6 số, gửi bằng `spring-boot-starter-mail`. Cấu hình SMTP lấy từ env; dev dùng Gmail App Password hoặc Mailpit trong docker-compose.
     - `POST /verify-otp`: trả về reset token dùng một lần.
     - `POST /reset-password`.
   - Bảng `password_reset_otp`: user_id, otp_hash (KHÔNG lưu OTP thô), expires_at (5 phút), attempts (tối đa 5), used, created_at.
   - Giới hạn gửi lại 60 giây mỗi lần.
   - Luôn trả cùng một thông báo dù email có tồn tại hay không, để tránh dò tài khoản.
   - Đổi mật khẩu xong thì thu hồi mọi refresh token.
   - Gửi mail bất đồng bộ (`@Async`). Email dạng HTML tiếng Việt đơn giản.
6. **CRUD user**
   - `GET /api/users/me` và `PUT /api/users/me`: sửa thông tin, đổi mật khẩu cần mật khẩu cũ.
   - Admin: list (có phân trang), tạo, sửa vai trò, khoá/mở khoá, xoá mềm.
   - Phân quyền bằng `@PreAuthorize`.
7. **Chung**
   - Prefix `/api/auth/**` và `/api/users/**`.
   - DTO tách khỏi entity. Lỗi trả JSON thống nhất qua `@RestControllerAdvice`.
   - CORS cho `http://localhost:3000` (web) và Expo.
   - Swagger UI bằng springdoc (bản tương thích Boot 4).
   - Test: unit test service và integration test luồng register → login → forgot → verify → reset. Dùng Testcontainers Postgres và GreenMail để giả SMTP.

## Ràng buộc

- **Dữ liệu y tế cá nhân:**
  - không log mật khẩu, OTP, token, số điện thoại hay họ tên;
  - không commit `.env` hay mật khẩu SMTP;
  - không đưa dữ liệu bệnh nhân thật vào seed hay test.
- Backend có git:
  - commit theo từng bước nhỏ, trên branch riêng (không làm trên main);
  - **hỏi tôi trước khi push**;
  - mỗi commit kết thúc bằng dòng `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Làm từng bước, sau mỗi bước chạy `./mvnw test` và báo kết quả thật. Không làm phần frontend trong phiên này; chỉ cuối phiên ghi file `docs/api-auth.md` mô tả endpoint để tôi nối web/app sau.
- Trước khi code, hãy đưa tôi xem kế hoạch ngắn (danh sách endpoint, bảng DB, các dependency sẽ thêm) để tôi duyệt.
