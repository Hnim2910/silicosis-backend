-- Gộp lượt khám + ảnh + kết quả thành một bảng predictions: mỗi dòng là MỘT lượt chẩn đoán, không sửa được
-- (đổi thông tin = tạo lượt mới). Ảnh (nếu có) lưu ngay trên dòng, tệp nằm ở thư mục ảnh (APP_STORAGE_IMAGES_DIR).
-- CẢNH BÁO: XOÁ dữ liệu lượt khám, ảnh, kết quả cũ (đã thống nhất chỉ là dữ liệu thử). Tệp ảnh cũ trên đĩa không
-- bị xoá tự động. Bảng users, patients, refresh_tokens, password_reset_otp không đổi.

DROP TABLE predictions;
DROP TABLE images;
DROP TABLE visits;

CREATE TABLE predictions (
    id                    UUID             PRIMARY KEY,
    user_id               UUID             NOT NULL REFERENCES users (id),
    exam_date             DATE             NOT NULL,                  -- mặc định = ngày gửi
    -- Đầu vào lâm sàng gửi cho model: {tên_cột: giá_trị} (predict/ml/new_way/MO_TA_API.md).
    -- Giới tính và năm sinh được chép từ hồ sơ tại thời điểm chẩn đoán.
    lam_sang              JSONB            NOT NULL DEFAULT '{}'::jsonb,
    -- Ảnh X-quang (không bắt buộc); storage_key là tên tệp ngẫu nhiên trên đĩa, không dùng tên gốc
    image_storage_key     VARCHAR(100),
    image_content_type    VARCHAR(30)
        CONSTRAINT predictions_image_type_check CHECK (image_content_type IN ('image/png', 'image/jpeg')),
    image_size_bytes      BIGINT           CHECK (image_size_bytes > 0),
    image_width           INTEGER          CHECK (image_width > 0),
    image_height          INTEGER          CHECK (image_height > 0),
    image_sha256          VARCHAR(64),                                -- hash sau khi đã bỏ EXIF
    -- Kết quả model New Way (nhãn ketqua)
    status                VARCHAR(10)      NOT NULL
        CONSTRAINT predictions_status_check CHECK (status IN ('SUCCESS', 'FAILED')),
    error_code            VARCHAR(40),
    p_lam_sang            DOUBLE PRECISION CHECK (p_lam_sang >= 0 AND p_lam_sang <= 1),
    nguong_lam_sang       DOUBLE PRECISION CHECK (nguong_lam_sang >= 0 AND nguong_lam_sang <= 1),
    bao_nguy_co_lam_sang  BOOLEAN,
    co_anh                BOOLEAN,
    p_anh                 DOUBLE PRECISION CHECK (p_anh >= 0 AND p_anh <= 1),
    p_ghep                DOUBLE PRECISION CHECK (p_ghep >= 0 AND p_ghep <= 1),
    nguong_ghep           DOUBLE PRECISION CHECK (nguong_ghep >= 0 AND nguong_ghep <= 1),
    bao_nguy_co_ghep      BOOLEAN,
    ket_luan_cuoi         BOOLEAN,
    model_name            VARCHAR(100),
    model_version         VARCHAR(64),
    created_by            UUID             REFERENCES users (id),
    deleted_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),

    -- Thông tin ảnh: có đủ hoặc không có gì
    CONSTRAINT predictions_image_check CHECK (
        (image_storage_key IS NULL AND image_content_type IS NULL AND image_size_bytes IS NULL
            AND image_width IS NULL AND image_height IS NULL AND image_sha256 IS NULL)
        OR (image_storage_key IS NOT NULL AND image_content_type IS NOT NULL AND image_size_bytes IS NOT NULL
            AND image_width IS NOT NULL AND image_height IS NOT NULL AND image_sha256 IS NOT NULL)),
    -- SUCCESS có đủ điểm lâm sàng (và điểm ảnh/ghép nếu có ảnh); FAILED chỉ có mã lỗi
    CONSTRAINT predictions_result_check CHECK (
        (status = 'SUCCESS' AND error_code IS NULL AND model_version IS NOT NULL
            AND p_lam_sang IS NOT NULL AND nguong_lam_sang IS NOT NULL AND bao_nguy_co_lam_sang IS NOT NULL
            AND co_anh IS NOT NULL AND ket_luan_cuoi IS NOT NULL
            AND co_anh = (image_storage_key IS NOT NULL)
            AND (co_anh = FALSE OR (p_anh IS NOT NULL AND p_ghep IS NOT NULL AND nguong_ghep IS NOT NULL
                                    AND bao_nguy_co_ghep IS NOT NULL)))
        OR (status = 'FAILED' AND error_code IS NOT NULL AND p_lam_sang IS NULL AND p_anh IS NULL
            AND p_ghep IS NULL AND ket_luan_cuoi IS NULL))
);

CREATE INDEX ix_predictions_user ON predictions (user_id, exam_date DESC, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_predictions_created ON predictions (exam_date DESC, created_at DESC) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_predictions_image_key ON predictions (image_storage_key) WHERE image_storage_key IS NOT NULL;
