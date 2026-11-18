-- Gộp hồ sơ bệnh nhân vào users: mỗi tài khoản USER chính là một bệnh nhân. Tài khoản ADMIN để trống các cột này.
-- Hồ sơ không gắn tài khoản (do ADMIN tạo) bị bỏ — đã thống nhất dữ liệu hiện có chỉ là dữ liệu thử.

ALTER TABLE users
    ADD COLUMN code             VARCHAR(20),     -- mã bệnh nhân hiển thị, dạng BN-000001; null với ADMIN
    ADD COLUMN workplace        VARCHAR(255),
    ADD COLUMN exposure_history TEXT,            -- tiền sử phơi nhiễm bụi
    ADD COLUMN risk_factors     TEXT;

-- Giữ mã và thông tin hồ sơ đang hoạt động của từng tài khoản (họ tên, ngày sinh, giới tính, nghề nghiệp
-- vốn đã được đồng bộ sẵn trong users).
UPDATE users u
SET code             = p.code,
    workplace        = p.workplace,
    exposure_history = p.exposure_history,
    risk_factors     = p.risk_factors
FROM patients p
WHERE p.user_id = u.id AND p.deleted_at IS NULL;

ALTER SEQUENCE patient_code_seq RENAME TO user_code_seq;

UPDATE users
SET code = 'BN-' || lpad(nextval('user_code_seq')::text, 6, '0')
WHERE role = 'USER' AND code IS NULL;

-- Mã không bao giờ dùng lại, kể cả của tài khoản đã xoá mềm.
CREATE UNIQUE INDEX ux_users_code ON users (code) WHERE code IS NOT NULL;

DROP TABLE patients;
