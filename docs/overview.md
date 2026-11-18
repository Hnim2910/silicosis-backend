# SilicoCare — Tài liệu tổng quan cho thành viên mới

> Đọc file này trước tiên. Mất khoảng 20 phút để nắm dự án làm gì, chạy thế nào và code nằm ở đâu.
> Nội dung đã đối chiếu với mã nguồn (cập nhật 18/11/2026, sau đợt gộp bảng `users` và `predictions`). Nếu code thay đổi, **code là nguồn đúng**, hãy sửa lại tài liệu này.

Mục lục:
1. [Mục đích dự án](#1-mục-đích-dự-án)
2. [Phạm vi và người dùng](#2-phạm-vi-và-người-dùng)
3. [Tech stack](#3-tech-stack)
4. [Tổng quan nghiệp vụ](#4-tổng-quan-nghiệp-vụ)
5. [Cơ sở dữ liệu](#5-cơ-sở-dữ-liệu)
6. [Kiến trúc và cấu trúc thư mục](#6-kiến-trúc-và-cấu-trúc-thư-mục)
7. [Hướng dẫn cấu hình và chạy dự án](#7-hướng-dẫn-cấu-hình-và-chạy-dự-án)
8. [Quy ước khi đóng góp](#8-quy-ước-khi-đóng-góp)
9. [Việc đang chờ quyết định](#9-việc-đang-chờ-quyết-định)

---

## 1. Mục đích dự án

**Bệnh bụi phổi silic** là bệnh nghề nghiệp do hít phải bụi silic (thường gặp ở công nhân khai thác, nghiền đá, mài, xây dựng). Bệnh tiến triển âm thầm, khó phát hiện sớm nếu không khám định kỳ và đọc phim X-quang chuyên khoa.

**SilicoCare** (đồ án tốt nghiệp) là hệ thống **hỗ trợ sàng lọc** bệnh này:

- Người dùng nhập **thông tin lâm sàng** (tuổi nghề, hút thuốc, ho, khó thở, chức năng hô hấp…) và có thể kèm **phim X-quang ngực thẳng**.
- **Mô hình AI** ("New Way") ước lượng nguy cơ: XGBoost cho phần lâm sàng; nếu có phim thì ghép thêm BioViL-T cho phần ảnh.
- Mỗi lần gửi được lưu thành một **lượt chẩn đoán** để theo dõi theo thời gian và xuất báo cáo PDF.

> ⚠️ **Công cụ sàng lọc thử nghiệm, không thay thế chẩn đoán của bác sĩ.** Cách diễn đạt nhãn kết quả đang được nhóm bàn lại (xem [mục 9](#9-việc-đang-chờ-quyết-định)). Trong lúc chờ, giữ nguyên nhãn hiện tại và không dùng chữ "dương tính" / "âm tính".

## 2. Phạm vi và người dùng

### 2.1. Phạm vi

Hệ thống dùng cho **một cơ sở y tế cụ thể**, không mở công khai cho mọi người.

### 2.2. Người dùng

Backend có **2 vai trò** (`Role.java`: `USER`, `ADMIN`). Web đổi tên hiển thị `USER` → `patient`, `ADMIN` → `admin`, việc đổi tên chỉ nằm trong `src/services/userMapper.ts`.

| Vai trò | Là ai | Làm được gì |
|---|---|---|
| **Bệnh nhân** (`USER`) | Người lao động có nguy cơ phơi nhiễm bụi silic. Mỗi tài khoản `USER` **chính là một hồ sơ bệnh nhân** (có mã `BN-000001`). | Sửa hồ sơ của mình, gửi lượt chẩn đoán, xem kết quả và lịch sử của mình, xuất PDF |
| **Quản trị viên** (`ADMIN`) | Người vận hành hệ thống của cơ sở; chỉ có **1 tài khoản**, seed từ `ADMIN_EMAIL` / `ADMIN_PASSWORD` | Quản lý tài khoản (tạo, khoá/mở khoá, xoá mềm); xem thống kê; *tạm thời* vẫn xem và xoá được mọi lượt chẩn đoán (xem mục 9) |

Lưu ý:
- **Không có vai trò bác sĩ.** Nhân viên y tế hỗ trợ bằng cách **thao tác hộ trên tài khoản của bệnh nhân**. Khi cần, admin tạo hộ tài khoản cho bệnh nhân.
- Người chưa đăng nhập chỉ thấy được trang đăng ký, đăng nhập và quên mật khẩu. Hệ thống không lưu "khách".
- Tự đăng ký hay admin tạo tài khoản thì kết quả đều là `USER`. Không có API đổi vai trò.
- Admin không tự khoá hay tự xoá được chính mình (lỗi `CANNOT_MODIFY_SELF`).

### 2.3. Chưa có (đừng tìm trong code)

- Vai trò bác sĩ, ghi chú chuyên môn.
- Bản đồ nhiệt giải thích (XAI), phân tích yếu tố ảnh hưởng.
- Thang mức nguy cơ chính thức từ nhóm ML.
- API thống kê riêng cho admin.
- App mobile (React Native, sẽ dùng chung API này).

## 3. Tech stack

| Thành phần | Công nghệ | Ghi chú |
|---|---|---|
| **Backend API** | Java 21, **Spring Boot 4.1.1**, Maven (`mvnw`) | Spring Security 7, Jakarta EE 11 — cú pháp mới, không còn `WebSecurityConfigurerAdapter` |
| Bảo mật | Spring Security + OAuth2 Resource Server (JWT HS256), BCrypt | Stateless |
| Dữ liệu | PostgreSQL 17, Spring Data JPA, **Flyway** | `ddl-auto: validate`: schema chỉ đổi qua file migration |
| Email | `spring-boot-starter-mail`, gửi `@Async` | Dev dùng **Mailpit** (SMTP giả) |
| Tài liệu API | springdoc-openapi 3.1.1 | `http://localhost:8080/swagger-ui.html` |
| Test backend | JUnit 5, **Testcontainers** (Postgres), **GreenMail** | Cần Docker |
| **Service AI** | Python, **FastAPI** + Uvicorn, PyTorch, XGBoost | Thư mục `predict/`, cổng 8001 |
| Model | XGBoost (lâm sàng) + BioViL-T 448 px × 3 seed (ảnh) | Trọng số ~330 MB **không có trong git** |
| **Frontend web** | **Next.js 16** (App Router), React 19, TypeScript 5, Tailwind CSS 4 | Thư mục `../sillicosis-web` |
| Thư viện web | `lucide-react` (icon), `jspdf` (PDF), `read-excel-file` (nhập lâm sàng từ Excel) | |
| Test web | Playwright, ESLint, Prettier | |
| Hạ tầng dev | Docker Compose: `postgres` + `mailpit` | `docker-compose.yml` |

## 4. Tổng quan nghiệp vụ

```
Đăng ký (được mã BN-xxxxxx) ──► Đăng nhập ──► Hồ sơ cá nhân (giới tính, ngày sinh, nghề…)
                                       │
                                       ▼
          Dự đoán mới: ngày khám + lâm sàng (+ tuỳ chọn phim X-quang)  ── 1 request ──►
                                       │
          Backend: kiểm tra → làm sạch ảnh → gọi service AI → lưu 1 dòng predictions
                                       │           (AI lỗi vẫn lưu, status = FAILED)
                                       ▼
          Xem kết quả · Thử lại nếu lỗi · Lịch sử · Xuất PDF
```

### 4.1. Đăng ký
- **Chức năng:** `POST /api/auth/register` với họ tên, email (bắt buộc), SĐT (tuỳ chọn) và mật khẩu.
- **Mục đích:** bệnh nhân tự tạo tài khoản, không cần chờ admin.
- **Quy tắc:**
  - Email hợp lệ và chưa dùng.
  - SĐT di động Việt Nam, lưu ở dạng `0xxxxxxxxx`.
  - Mật khẩu 8–72 ký tự, có ít nhất 1 chữ và 1 số.
  - Tài khoản được cấp ngay **mã bệnh nhân** `BN-000001`, mã không đổi và không dùng lại.
- **Ví dụ:** anh Nam, công nhân nghiền đá, đăng ký và có ngay tài khoản `BN-000012`.

### 4.2. Đăng nhập, phiên, khoá tạm
- **Chức năng:**
  - Đăng nhập bằng **email hoặc SĐT**: `POST /api/auth/login`.
  - Nhận access token JWT (15 phút) và refresh token (7 ngày).
  - Gia hạn phiên: `POST /api/auth/refresh`. Đăng xuất: `POST /api/auth/logout`.
- **Quy tắc:**
  - Sai mật khẩu **5 lần liên tiếp** thì tài khoản bị khoá **15 phút**.
  - Refresh token lưu trong DB dạng **hash**, nên thu hồi được.
  - Đổi hoặc đặt lại mật khẩu, bị khoá hoặc bị xoá → **thu hồi mọi phiên**.
- **Phía web:** access token chỉ giữ trong bộ nhớ của tab; refresh token lưu trong `localStorage` (`src/lib/apiClient.ts`).

### 4.3. Quên mật khẩu bằng OTP
- **Ba bước:**
  1. `POST /api/auth/forgot-password`: gửi **OTP 6 số** qua email.
  2. `POST /api/auth/verify-otp`: nhập OTP để nhận **reset token** dùng một lần (hạn 15 phút).
  3. `POST /api/auth/reset-password`: đặt mật khẩu mới.
- **Quy tắc:**
  - OTP hết hạn sau 5 phút, cho sai tối đa 5 lần, chỉ gửi lại được sau 60 giây.
  - DB chỉ lưu hash của OTP.
  - Bước 1 luôn trả cùng một thông báo, dù email có tồn tại hay không.

### 4.4. Hồ sơ cá nhân (kiêm hồ sơ bệnh nhân)
- **Chức năng:** `GET` / `PUT /api/users/me`, đổi mật khẩu bằng `PUT /api/users/me/password` (cần mật khẩu cũ).
- **Dữ liệu:**
  - **thông tin tiểu sử, ít thay đổi:** họ tên, email, SĐT, ngày sinh, giới tính, nghề nghiệp, nơi làm việc, tiền sử phơi nhiễm bụi, yếu tố nguy cơ;
  - mã bệnh nhân (chỉ đọc).
- **Mục đích:** không phải nhập lại thông tin cố định mỗi lần chẩn đoán. Cụ thể:
  - **giới tính và năm sinh** được backend tự chép vào đầu vào của model;
  - **nghề nghiệp** được điền sẵn vào ô "Công việc hiện tại" của form.
- **Ví dụ:** chị Lan cập nhật hồ sơ "Nữ, sinh 1980, thợ mài đá". Từ đó form dự đoán không hỏi lại giới tính và năm sinh nữa.

### 4.5. Lượt chẩn đoán
- **Chức năng:** `POST /api/predictions` nhận **một request multipart** gồm:
  - phần `data`: ngày khám và `lamSang`, tức các chỉ số lâm sàng;
  - phần `file`: phim X-quang, không bắt buộc.

  Backend chạy model rồi lưu lại. Chi tiết xem [api-predictions.md](api-predictions.md).
- **Ngữ nghĩa:**
  - Mỗi lượt chẩn đoán là **một lần khám, một kết quả**, và **không sửa được**. Đổi thông tin thì gửi lượt mới.
  - Mỗi lượt gồm thông tin động theo lần khám (`lamSang`), ảnh (nếu có) và kết quả.
- **Quy tắc:**
  - Phải có ít nhất ảnh hoặc một thông tin lâm sàng (nếu không: 400 `EMPTY_SUBMISSION`).
  - Mã lâm sàng phải đúng danh sách của model, ví dụ `"Co"`, `"Khong"`. Backend kiểm tra theo `GET /schema` của service AI.
  - **Ảnh:**
    - chỉ PNG/JPEG (xét theo nội dung tệp), ≤ 10 MB, cạnh ngắn ≥ 256 px;
    - được mã hoá lại để **xoá EXIF**;
    - web bắt người dùng tick **"Đây là phim X-quang ngực thẳng"**, vì model chỉ học trên loại phim này.
  - **Service AI lỗi:** lượt vẫn được lưu với `status = FAILED`, và nút **Thử lại** chạy lại model ngay trên lượt đó.
  - **Xoá:** xoá mềm.
- **Ví dụ:** tháng 3, anh Nam khám định kỳ, nhập "ho có, khó thở khi gắng sức, FVC 72,5%" kèm phim và được lượt chẩn đoán #1. Tháng 9 khám lại thì gửi lượt #2. Trang Lịch sử cho thấy cả hai.

### 4.6. Kết quả của model (New Way)
- **Chỉ lâm sàng:**
  - XGBoost cho ra `p_lam_sang`;
  - `p_lam_sang` ≥ ngưỡng `0,051546` thì báo có nguy cơ.
- **Có phim:**
  - BioViL-T cho ra `p_anh`, ghép với lâm sàng thành `p_ghep`;
  - `p_ghep` ≥ ngưỡng `0,207134` thì báo có nguy cơ;
  - khi có phim, kết luận cuối luôn theo `p_ghep`.
- **Vì sao ngưỡng thấp, không phải 0,5:**
  - Chỉ khoảng 7–8% số người trong dữ liệu là dương.
  - Ngưỡng được chọn để bắt được ≥ 80% số ca bệnh thật (ưu tiên không bỏ sót).
  - Hai luồng học trên hai nhóm người khác nhau, nên **không so sánh trực tiếp xác suất của hai luồng**.
- **Hiển thị hiện tại trên web** (chưa chốt, xem mục 9):
  - Có phim: hiện % điểm ghép.
  - Chỉ lâm sàng: hiện 3 mức thấp / trung bình / cao, mốc trên tạm đặt 0,15 trong `src/constants/app.ts`.
- **Hiệu năng trên tập test** (từ `predict/README.md`):

  | Luồng | ROC-AUC | Độ nhạy | Độ đặc hiệu |
  |---|---|---|---|
  | Chỉ lâm sàng | 0,64 | 0,86 | 0,34 |
  | Ảnh + lâm sàng | 0,82 | 0,82 | 0,62 |

  Luồng chỉ lâm sàng yếu; tín hiệu mạnh đến từ ảnh.

### 4.7. Quản lý tài khoản (Admin)
- **Chức năng:**
  - `GET /api/users`: danh sách, có phân trang, lọc theo vai trò và từ khoá;
  - `POST /api/users`: tạo tài khoản bệnh nhân;
  - `PATCH /api/users/{id}/status`: khoá / mở khoá;
  - `DELETE /api/users/{id}`: xoá mềm.
- **Quy tắc:** xoá mềm giữ lại hồ sơ và các lượt chẩn đoán (dữ liệu y tế). Mở khoá thì xoá luôn khoá tạm do đăng nhập sai.
- **Ví dụ:** bác Hùng không dùng smartphone. Admin tạo tài khoản cho bác, sau đó nhân viên y tế đăng nhập tài khoản đó để nhập lượt chẩn đoán hộ.

### 4.8. Tổng quan, lịch sử, báo cáo (phía web)
- **Tổng quan** (`/dashboard`):
  - hiện số lượt chẩn đoán và phân bố kết quả;
  - các số liệu được **tính ở trình duyệt** từ danh sách lượt.
- **Lịch sử** (`/history`): lọc theo kết quả và ngày khám.
- **Báo cáo PDF:** tạo ở trình duyệt bằng `jspdf`, chỉ cho lượt thành công.
- **Nhập lâm sàng từ file** (`.json`, `.csv`, `.xlsx`, `.txt`):
  - điền nhanh form;
  - file chỉ được đọc trên máy, không gửi lên server.

## 5. Cơ sở dữ liệu

PostgreSQL. Schema do **Flyway** quản lý trong `src/main/resources/db/migration/`:

| File | Nội dung |
|---|---|
| `V1__init_auth.sql` | Bảng ban đầu: `users`, `refresh_tokens`, `password_reset_otp`, `images`, `patients`, `visits` |
| `V2`, `V3` | `predictions` của model cũ / bổ sung cột New Way |
| `V4__predictions_merge_visits_images.sql` | **Gộp** `visits` + `images` + `predictions` thành bảng `predictions` mới (xoá dữ liệu lượt khám cũ) |
| `V5__merge_patients_into_users.sql` | **Gộp** `patients` vào `users` (giữ mã BN), đổi `patient_code_seq` thành `user_code_seq` |

Quy ước:
- khoá chính là UUID, thời gian dùng `TIMESTAMPTZ` (UTC);
- xoá mềm bằng `deleted_at`;
- không lưu bí mật dạng thô: mật khẩu và OTP lưu bằng BCrypt, token lưu dạng hash.

### 5.1. Sơ đồ quan hệ

```
                 ┌────────────────────────────┐
                 │           users            │  tài khoản + hồ sơ bệnh nhân
                 └──────────────┬─────────────┘
        1 ── n ┌────────────────┼──────────────────┐ 1 ── n
               ▼                ▼ 1 ── n            ▼
     ┌────────────────┐  ┌─────────────┐  ┌────────────────────┐
     │ refresh_tokens │  │ predictions │  │ password_reset_otp │
     └────────────────┘  └─────────────┘  └────────────────────┘
                          (mỗi dòng = 1 lượt chẩn đoán: đầu vào + ảnh + kết quả)
```

`predictions.created_by` cũng trỏ về `users`: người đã gửi lượt chẩn đoán đó.

### 5.2. Mô tả từng bảng

| Bảng | Ý nghĩa | Cột đáng chú ý |
|---|---|---|
| `users` | Tài khoản kiêm hồ sơ bệnh nhân | `email` (unique, chữ thường), `phone` (unique, tuỳ chọn), `password_hash`, `role` (`USER`/`ADMIN`), `enabled`, `failed_login_attempts` + `locked_until`; **hồ sơ:** `code` (BN-…, null với ADMIN), `birth_date`, `gender`, `occupation`, `workplace`, `exposure_history`, `risk_factors`; `deleted_at` |
| `predictions` | Lượt chẩn đoán | `user_id`, `exam_date`, **`lam_sang` (JSONB)**: đầu vào model, đã kèm giới tính/năm sinh chụp từ hồ sơ; **ảnh** `image_storage_key`, `image_content_type`, `image_width`, `image_height`, `image_sha256` (đủ cả hoặc null cả); **kết quả** `status` (`SUCCESS`/`FAILED`), `error_code`, `p_lam_sang`, `p_anh`, `p_ghep`, các ngưỡng, `ket_luan_cuoi`, `model_version`; `deleted_at` |
| `refresh_tokens` | Refresh token đang phát hành | `token_hash` (SHA-256), `expires_at`, `revoked_at` |
| `password_reset_otp` | Mỗi lần yêu cầu quên mật khẩu | `otp_hash`, `expires_at`, `attempts`, `used`, `reset_token_hash` |

Ràng buộc trong DB (`CHECK`) bảo đảm hai điều:
- lượt `SUCCESS` có đủ điểm số, và có điểm ảnh khi và chỉ khi có ảnh;
- lượt `FAILED` chỉ có mã lỗi.

> Tệp ảnh **không** nằm trong DB mà ở thư mục `APP_STORAGE_IMAGES_DIR` (mặc định `~/.silicocare/images`), tên tệp là UUID ngẫu nhiên.

## 6. Kiến trúc và cấu trúc thư mục

### 6.1. Kiến trúc tổng thể

```
 ┌──────────────────────┐      ┌───────────────────────┐
 │  Web Next.js :3000   │      │ App React Native      │
 │  (sillicosis-web)    │      │ (sẽ làm sau)          │
 └──────────┬───────────┘      └───────────┬───────────┘
            │ JSON / multipart, Authorization: Bearer <JWT>
            ▼                              ▼
 ┌─────────────────────────────────────────────────────┐
 │        Backend Spring Boot :8080 (silicosis-backend)│
 │  Controller → Service → Repository (JPA)            │
 └───┬──────────────┬───────────────┬──────────────┬───┘
     │ JDBC         │ SMTP          │ HTTP nội bộ  │ đọc/ghi tệp ảnh
     ▼              ▼               ▼              ▼
 ┌──────────┐ ┌─────────────┐ ┌──────────────────┐ ┌──────────────┐
 │PostgreSQL│ │Mailpit/Gmail│ │ FastAPI :8001    │ │ Thư mục ảnh  │
 └──────────┘ └─────────────┘ │ XGBoost+BioViL-T │ └──────────────┘
                              └──────────────────┘
```

- **Web không bao giờ gọi thẳng FastAPI.** Service AI chỉ nghe trên `127.0.0.1`, không có xác thực.
- Backend gọi AI **ngoài transaction**; dòng `predictions` chỉ được ghi sau khi có kết quả.
- Service AI không lưu ảnh hay dữ liệu lâm sàng.

### 6.2. Backend — `silicosis-backend/`

Code chia theo **tính năng**. Mỗi thư mục gồm `XxxController` (nhận HTTP, kiểm tra quyền), `XxxService` (quy tắc nghiệp vụ), entity, repository và thư mục `dto/` (object vào/ra API; **không trả entity ra ngoài**).

```
silicosis-backend/
├── pom.xml, mvnw(.cmd)          Dependency Maven + wrapper (không cần cài Maven)
├── docker-compose.yml           Postgres 17 + Mailpit cho dev
├── .env.example                 Mẫu biến môi trường → copy thành .env (KHÔNG commit .env)
├── Claude.md, * endpoint.md     Đặc tả/prompt từng giai đoạn (lịch sử thiết kế, có đoạn đã cũ)
├── docs/
│   ├── overview.md              ← file này
│   ├── api-auth.md              Đăng ký, đăng nhập, token, OTP, hồ sơ, quản lý user, định dạng lỗi
│   └── api-predictions.md       Lượt chẩn đoán: gửi, xem, ảnh, thử lại, mã lỗi
│
├── src/main/java/com/sillicosis/backend/
│   ├── BackendApplication.java  Điểm khởi động; bật @EnableAsync (gửi mail nền)
│   ├── config/
│   │   ├── SecurityConfig.java      Đường dẫn công khai, JWT, CORS, BCrypt
│   │   ├── AppProperties.java       Mọi cấu hình app.* (TTL token, OTP, khoá, ảnh, hồ sơ, AI) + mặc định
│   │   ├── DelegatingErrorHandlers  Đưa lỗi 401/403 ở tầng filter về cùng định dạng JSON
│   │   ├── OpenApiConfig.java       Swagger (nút Authorize Bearer)
│   │   └── ClockConfig.java         Bean Clock — test giả lập được thời gian
│   ├── common/
│   │   ├── GlobalExceptionHandler   Mọi lỗi → JSON {status, code, message, fieldErrors}
│   │   ├── ApiError, ApiException   Định dạng lỗi và cách ném lỗi nghiệp vụ
│   │   ├── PageResponse             Bọc kết quả phân trang
│   │   ├── VnPhone, PhoneNumbers    Kiểm tra / chuẩn hoá SĐT Việt Nam
│   │   ├── StrongPassword           Kiểm tra mật khẩu mạnh
│   │   └── TokenHasher              SHA-256 cho refresh/reset token
│   ├── auth/                    /api/auth/**: đăng ký, đăng nhập, refresh, logout, quên mật khẩu
│   │   ├── AuthService              Đăng ký (gán mã BN), đăng nhập email/SĐT, khoá sau 5 lần sai
│   │   ├── TokenService             Phát JWT, xoay/thu hồi refresh token
│   │   └── PasswordResetService     Luồng OTP
│   ├── mail/MailService.java    Gửi mail OTP HTML tiếng Việt, @Async sau khi commit
│   ├── user/                    Tài khoản kiêm hồ sơ bệnh nhân
│   │   ├── UserController           /api/users/me — hồ sơ của tôi, đổi mật khẩu
│   │   ├── AdminUserController      /api/users — quản lý tài khoản (chỉ ADMIN)
│   │   ├── UserService              Sửa hồ sơ, tạo/khoá/xoá tài khoản
│   │   ├── UserAccounts             Chuẩn hoá email, kiểm tra trùng, gán mã bệnh nhân
│   │   ├── AdminSeeder              Tạo admin từ ADMIN_EMAIL/ADMIN_PASSWORD khi khởi động
│   │   └── User, Role, Gender       Entity và enum
│   ├── prediction/              ★ Lượt chẩn đoán: /api/predictions
│   │   ├── PredictionController     Gửi (multipart), danh sách, chi tiết, ảnh, thử lại, xoá
│   │   ├── PredictionService        Kiểm tra đầu vào, chép giới tính/năm sinh từ hồ sơ, gọi model, lưu
│   │   ├── Prediction               Entity: đầu vào + ảnh + kết quả trên một dòng
│   │   ├── PredictClient            HTTP client gọi FastAPI (/predict/clinical, /predict/clinical-image, /schema)
│   │   ├── ClinicalValidator        Kiểm tra lamSang theo /schema trước khi lưu
│   │   └── PredictException         Mã lỗi khi service AI lỗi (lưu vào error_code)
│   └── image/
│       ├── ImageProcessor           Kiểm tra định dạng/kích thước, xoá EXIF, tính SHA-256
│       └── ImageStorage             Đọc/ghi tệp ảnh trên đĩa
│
├── src/main/resources/
│   ├── application.yaml         Cấu hình; giá trị nhạy cảm đọc từ biến môi trường / .env
│   └── db/migration/            Flyway V1…V5 (chỉ THÊM file mới, không sửa file cũ)
│
├── src/test/                    Unit test service + integration test (Testcontainers, GreenMail, server AI giả)
│   ├── AuthFlowIntegrationTest  register → login → forgot → verify → reset
│   └── prediction/PredictionApiIntegrationTest   Gửi, xem, ảnh, thử lại, phân quyền, lỗi service AI
│
└── predict/                     Service AI (Python/FastAPI) — chạy riêng, cổng 8001
    ├── app/main.py              /health, /schema, /predict/clinical, /predict/clinical-image
    ├── app/new_way.py           Kiểm tra lâm sàng, gọi gói model
    ├── ml/new_way/              Gói model nhóm ML bàn giao — KHÔNG sửa
    │   ├── MO_TA_API.md         ★ Danh sách cột lâm sàng và mã hợp lệ
    │   └── mo_hinh/             Trọng số (~330 MB) — KHÔNG có trong git
    └── README.md                Cách chạy + giới hạn mô hình
```

**Một request tạo lượt chẩn đoán đi qua backend như sau:**

```
POST /api/predictions (multipart: data + file)
  → SecurityConfig: xác thực JWT, lấy userId
  → PredictionController.create
  → PredictionService.create: kiểm tra ngày khám, lamSang (ClinicalValidator), ảnh (ImageProcessor)
  → chép giới tính/năm sinh từ users → PredictClient → FastAPI (ngoài transaction)
  → ghi tệp ảnh (ImageStorage) → lưu 1 dòng predictions → 201 PredictionResponse
  Lỗi bất kỳ → GlobalExceptionHandler → JSON {status, code, message, fieldErrors}
```

### 6.3. Frontend — `../sillicosis-web/`

> Tên thư mục là `sillicosis-web` (hai chữ **l**).

Nguyên tắc chung:
- **Component không gọi `fetch` trực tiếp.** Mọi lời gọi API đi qua `src/services/*`, rồi tới `src/lib/apiClient.ts`.
- Alias import: `@/components/*` trỏ tới `components/`, còn `@/*` trỏ tới `src/`.

**Bố cục giao diện:**
- header chạy hết chiều rộng màn hình, có logo bên trái và avatar bên phải;
- sidebar menu nằm dưới header;
- cỡ chữ thống nhất: tiêu đề trang 22px, mục menu và tiêu đề thẻ 16px, chữ thường 14px;
- màu chữ và icon đạt tương phản ≥ 4.5:1.

```
sillicosis-web/
├── package.json                 Script: dev, build, lint, format, test:e2e
├── .env.example                 NEXT_PUBLIC_API_URL (URL backend) + tài khoản test E2E
├── AGENTS.md                    Cảnh báo: Next.js 16 khác bản cũ, đọc docs trong node_modules/next
├── Nghiệp vụ.docx, docs/        Tài liệu nghiệp vụ gốc, kiến trúc, kiểm thử (một số đoạn đã cũ)
├── src/
│   ├── app/                     Routing (App Router) — chỉ ghép trang
│   │   ├── login/, register/, forgot-password/   Trang công khai
│   │   └── (workspace)/             Trang cần đăng nhập, dùng chung MainLayout
│   │       ├── dashboard/               Tổng quan
│   │       ├── prediction/              Dự đoán mới; prediction/[id] = kết quả một lượt
│   │       ├── history/                 Lịch sử; history/[id] = chi tiết
│   │       ├── profile/                 Hồ sơ cá nhân + đổi mật khẩu
│   │       └── admin/users, admin/records   (Admin) tài khoản, mọi lượt chẩn đoán
│   ├── lib/apiClient.ts         ★ Gửi request, gắn Bearer, tự refresh khi 401, chuẩn hoá lỗi
│   ├── contexts/AuthContext.tsx Người dùng đang đăng nhập (kèm hồ sơ)
│   ├── services/
│   │   ├── authService.ts           Đăng nhập, đăng ký, OTP, cập nhật hồ sơ
│   │   ├── userMapper.ts            Đổi dạng dữ liệu user backend ↔ web
│   │   ├── predictionService.ts     ★ Gửi lượt chẩn đoán (multipart), danh sách, chi tiết, ảnh, thử lại
│   │   ├── imageService.ts          Kiểm tra ảnh trên máy trước khi gửi
│   │   ├── userService.ts           Quản lý tài khoản (admin)
│   │   ├── dashboardService.ts      Đếm số liệu tổng quan ở trình duyệt
│   │   └── reportService.ts         Xuất PDF
│   ├── constants/
│   │   ├── clinicalFields.ts        ★ Các trường lâm sàng của model (khớp MO_TA_API.md)
│   │   ├── navigation.ts            Menu + vai trò nào thấy mục nào
│   │   └── app.ts                   Nhãn kết quả, câu khuyến nghị, thông báo lỗi
│   ├── types/                   prediction.ts, user.ts
│   ├── utils/                   predictionDisplay (hiển thị kết quả), clinicalForm, clinicalImport, format, validation
│   ├── hooks/                   useAsync, useImageUrl (tải ảnh kèm token)
│   └── styles/                  base, layout, workspace, auth, responsive
├── components/
│   ├── common/                  Button, Input, Modal, Table, Card, Badge, Loading, Feedback, PageHeader, Brand
│   ├── layout/                  Header (logo + avatar), Sidebar (menu + đăng xuất), MainLayout
│   ├── auth/                    Form đăng nhập / đăng ký / quên mật khẩu
│   ├── dashboard/               Thẻ số liệu, biểu đồ phân bố, lối tắt
│   ├── prediction/              ★ PredictionWorkspace (form), ClinicalFields, ClinicalImport, XrayUpload,
│   │                              XrayViewer, PredictionResult, PredictionHistory
│   ├── profile/                 ProfileForm (tài khoản + hồ sơ bệnh nhân), PasswordForm
│   └── admin/                   Quản lý tài khoản
└── tests/                       Playwright: unit.spec, clinicalImport.spec, live.spec (cần backend thật)
```

## 7. Hướng dẫn cấu hình và chạy dự án

Giả sử hai repo nằm cạnh nhau: `webapp/silicosis-backend` và `webapp/sillicosis-web`.

### Bước 0 — Cài công cụ

JDK **21** · Docker Desktop (bắt buộc cho DB dev và cho test backend) · Node.js ≥ 20 · Python 3.x (cho service AI, tuỳ chọn) · Git. Không cần cài Maven.

### Bước 1 — Cấu hình backend

```powershell
cd silicosis-backend
copy .env.example .env        # macOS/Linux: cp .env.example .env
```

Sửa tối thiểu trong `.env`:

```properties
DB_PASSWORD=matkhau-tuy-chon
JWT_SECRET=<chuỗi ngẫu nhiên ≥ 32 ký tự>    # thiếu hoặc ngắn → backend KHÔNG khởi động
ADMIN_EMAIL=admin@example.com
ADMIN_PASSWORD=Admin12345                   # ≥ 8 ký tự

# Dùng Mailpit thay Gmail khi dev:
MAIL_HOST=localhost
MAIL_PORT=1025
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_SMTP_AUTH=false
MAIL_STARTTLS=false
```

`DB_URL` phải trỏ đúng cổng Postgres. `docker-compose.yml` mặc định dùng cổng `5432`; nếu đổi cổng ở đó thì sửa `DB_URL` cho khớp.

### Bước 2 — Chạy Postgres + Mailpit và backend

```powershell
docker compose --env-file .env up -d     # Postgres, Mailpit (SMTP :1025, web :8025)
.\mvnw.cmd spring-boot:run               # macOS/Linux: ./mvnw spring-boot:run
```

Flyway tự tạo hoặc nâng schema, `AdminSeeder` tự tạo tài khoản admin.
- Swagger: <http://localhost:8080/swagger-ui.html>. Bấm **Authorize** rồi dán `accessToken`.
- Hộp thư OTP: <http://localhost:8025>.

> ⚠️ Backend chạy bằng `spring-boot:run` có **devtools**: mỗi lần code được biên dịch lại (kể cả khi chạy `mvnw test`), backend tự khởi động lại và **chạy migration mới lên DB dev** đang trỏ tới. Nếu muốn giữ dữ liệu, hãy tắt backend hoặc sao lưu DB trước khi pull code có migration mới.

### Bước 3 — (Tuỳ chọn) Chạy service AI

Nếu bỏ qua bước này, lượt chẩn đoán vẫn được lưu nhưng `FAILED` (có nút Thử lại). Muốn tắt hẳn thì đặt `PREDICT_ENABLED=false`.

1. Xin nhóm ML thư mục `mo_hinh/` (~330 MB) và chép vào `predict/ml/new_way/mo_hinh/`.
2. Chạy:

```powershell
cd predict
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

3. Mở <http://127.0.0.1:8001/health>, kết quả đúng phải có `"model_loaded": true`. Chi tiết xem [predict/README.md](../predict/README.md).

### Bước 4 — Chạy frontend

```powershell
cd ..\sillicosis-web
npm ci
copy .env.example .env.local     # NEXT_PUBLIC_API_URL=http://localhost:8080
npm run dev
```

Mở <http://localhost:3000>.
- Đổi `NEXT_PUBLIC_API_URL` thì phải khởi động lại `npm run dev`.
- Web chạy ở origin khác `localhost:3000` thì thêm origin đó vào `APP_CORS_ALLOWED_ORIGIN_PATTERNS` của backend.

### Bước 5 — Thử nhanh

1. **Đăng ký** một tài khoản bằng email giả. Vào **Hồ sơ cá nhân**, điền giới tính, ngày sinh, nghề nghiệp.
2. Vào **Dự đoán mới**. Form không hỏi lại giới tính và năm sinh, còn "Công việc hiện tại" đã được điền sẵn. Nhập vài trường (thêm ảnh nếu có) rồi bấm **Chẩn đoán**.
3. Xem kết quả, rồi vào **Lịch sử**. Thử **Quên mật khẩu**, lấy OTP ở <http://localhost:8025>.
4. Đăng nhập bằng tài khoản admin để xem **Quản lý tài khoản**.

> Chỉ dùng **dữ liệu giả**, không đưa dữ liệu bệnh nhân thật vào seed, test hay ảnh chụp màn hình.

### Bước 6 — Chạy test

```powershell
.\mvnw.cmd test                          # backend; Docker phải đang chạy (Testcontainers dùng DB riêng)
cd predict; .\.venv\Scripts\python -m pytest -q
cd ..\..\sillicosis-web
npm run lint
npm run build
npx playwright test tests/unit.spec.ts tests/clinicalImport.spec.ts   # không cần backend
npm run test:e2e                         # đủ bộ; live.spec cần backend + tài khoản test trong .env.local
```

### Lỗi thường gặp

| Hiện tượng | Nguyên nhân / cách xử lý |
|---|---|
| `JWT_SECRET phải được đặt và dài tối thiểu 32 byte` | Chưa sửa `JWT_SECRET` trong `.env` |
| `Hãy đặt DB_PASSWORD trong .env` khi `docker compose up` | Thiếu `DB_PASSWORD` hoặc quên `--env-file .env` |
| Backend báo lỗi Flyway / `validate` schema | Entity và migration lệch nhau, hoặc DB đã chạy migration của nhánh khác. Dev có thể làm mới bằng `docker compose down -v` (**mất dữ liệu local**) |
| Không nhận được mail OTP | Cấu hình `MAIL_*` chưa trỏ tới Mailpit, hoặc chưa đủ 60 giây từ lần gửi trước |
| Web báo lỗi CORS | Origin của web chưa có trong `APP_CORS_ALLOWED_ORIGIN_PATTERNS` |
| Lượt chẩn đoán luôn `FAILED` | Service AI chưa chạy ở `PREDICT_BASE_URL`, hoặc thiếu file trong `mo_hinh/` (xem `/health`) |
| 415 khi gửi lượt chẩn đoán | Request không phải multipart, hoặc phần `data` thiếu `type: application/json` |

## 8. Quy ước khi đóng góp

- **Git:** không làm trên `main`; dùng branch riêng (ví dụ `quandt/main`, `quandt/newUI`), commit nhỏ. Chạy `./mvnw test` trước khi đẩy code.
- **DB:** đổi schema bằng cách thêm file `V<n+1>__mo_ta.sql`. **Không sửa** file migration đã chạy.
- **API:**
  - controller chỉ nhận và trả **DTO**;
  - lỗi nghiệp vụ ném `ApiException` với `code` rõ ràng, vì web dựa vào `code` để hiển thị;
  - không có quyền thì trả 404, không trả 403, để không lộ dữ liệu có tồn tại.
- **Dữ liệu y tế:** không log mật khẩu, OTP, token, SĐT, họ tên, giá trị lâm sàng hay điểm số. Không commit `.env`, mật khẩu SMTP hay file trọng số.
- **Thêm hoặc đổi API:** cập nhật `docs/api-*.md` tương ứng.
- **Trường lâm sàng:** nếu đổi, phải khớp ở 3 nơi:
  - `predict/ml/new_way/MO_TA_API.md`;
  - `/schema` của service AI (ClinicalValidator đọc từ đây);
  - `src/constants/clinicalFields.ts` bên web.

## 9. Việc đang chờ quyết định

| Việc | Hiện trạng trong code |
|---|---|
| **Cách hiển thị kết quả** (giữ khung sàng lọc, hay dùng % kèm 4 mức nguy cơ) | Giữ như cũ. Mức "cao" của luồng chỉ lâm sàng tạm đặt 0,15, chưa được nhóm ML kiểm định. Cần nhóm ML gửi bảng ngưỡng trên tập test cho từng luồng |
| **Giới hạn quyền admin** (chỉ quản lý tài khoản và thống kê, không xem dữ liệu y tế) | Chưa làm. Admin vẫn xem/xoá được mọi lượt chẩn đoán (`GET /api/predictions/all`). Trang "Hồ sơ bệnh nhân" của admin đã bỏ do gộp bảng |
| **Bệnh nhân không có email** | Email vẫn bắt buộc; quên mật khẩu chỉ qua email |
| **Thao tác hộ trên tài khoản bệnh nhân** | Hệ thống không phân biệt được ai thao tác (chủ tài khoản hay nhân viên y tế). Đây là giới hạn đã biết |

Tài liệu cũ cần lưu ý:
- `Claude.md` và các file `* endpoint.md` là đặc tả theo từng giai đoạn: đường dẫn cũ (`E:\Hoc hanh\...`), còn nhắc `patients`, `visits`, `images`.
- `sillicosis-web/README.md` và `docs/architecture.md` vẫn mô tả mô hình chỉ đọc ảnh và hồ sơ bệnh nhân tách riêng.
