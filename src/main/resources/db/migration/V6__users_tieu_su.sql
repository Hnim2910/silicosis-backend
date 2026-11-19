-- Tiểu sử bệnh nhân: các trường ổn định qua nhiều lần khám, dùng đúng mã cột của form lâm sàng
-- (cviec, pxuong, tuoinghe, ..., hutthuoc, tiensuhh, tsnoi, tsngoai). Giới tính và năm sinh vẫn lấy từ
-- users.gender / users.birth_date. Thay cho bốn cột hồ sơ tự do thêm ở V5.

ALTER TABLE users ADD COLUMN tieu_su JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Nghề nghiệp cũ chuyển thành "Công việc hiện tại".
UPDATE users
SET tieu_su = jsonb_build_object('cviec', btrim(occupation))
WHERE occupation IS NOT NULL AND btrim(occupation) <> '';

ALTER TABLE users DROP COLUMN occupation;
ALTER TABLE users DROP COLUMN workplace;
ALTER TABLE users DROP COLUMN exposure_history;
ALTER TABLE users DROP COLUMN risk_factors;
