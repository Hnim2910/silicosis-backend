-- Bỏ mô hình New Way, mọi lượt chẩn đoán chỉ dùng mô hình PG (nhãn ketqua).
-- PG chỉ trả xác suất đã hiệu chỉnh: KHÔNG có ngưỡng, KHÔNG có kết luận dương/âm (ngưỡng PG chưa được duyệt).
-- CẢNH BÁO: XOÁ mọi lượt chẩn đoán cũ (đã thống nhất chỉ là dữ liệu thử). Tệp ảnh cũ trên đĩa không bị xoá tự động.
-- Bảng users, refresh_tokens, password_reset_otp không đổi.

DELETE FROM predictions;

ALTER TABLE predictions DROP CONSTRAINT predictions_result_check;

ALTER TABLE predictions
    DROP COLUMN p_lam_sang,
    DROP COLUMN nguong_lam_sang,
    DROP COLUMN bao_nguy_co_lam_sang,
    DROP COLUMN p_anh,
    DROP COLUMN p_ghep,
    DROP COLUMN nguong_ghep,
    DROP COLUMN bao_nguy_co_ghep,
    DROP COLUMN ket_luan_cuoi;

ALTER TABLE predictions
    -- Xác suất ketqua = 1 (trung bình các mô hình fold)
    ADD COLUMN xac_suat           DOUBLE PRECISION CHECK (xac_suat >= 0 AND xac_suat <= 1),
    -- Xác suất của từng mô hình fold, mảng số
    ADD COLUMN xac_suat_theo_fold JSONB CHECK (jsonb_typeof(xac_suat_theo_fold) = 'array'),
    -- Mức chú ý trên 6 vùng phổi {RU, RM, RL, LU, LM, LL}; null khi không có ảnh. Chỉ để tham khảo.
    ADD COLUMN chu_y_vung         JSONB CHECK (jsonb_typeof(chu_y_vung) = 'object'),
    -- Cảnh báo tiếng Việt do mô hình sinh, mảng chuỗi
    ADD COLUMN canh_bao           JSONB CHECK (jsonb_typeof(canh_bao) = 'array');

-- SUCCESS có xác suất; FAILED chỉ có mã lỗi
ALTER TABLE predictions ADD CONSTRAINT predictions_result_check CHECK (
    (status = 'SUCCESS' AND error_code IS NULL AND model_version IS NOT NULL
        AND xac_suat IS NOT NULL AND xac_suat_theo_fold IS NOT NULL AND canh_bao IS NOT NULL
        AND co_anh IS NOT NULL AND co_anh = (image_storage_key IS NOT NULL)
        AND (co_anh = TRUE OR chu_y_vung IS NULL))
    OR (status = 'FAILED' AND error_code IS NOT NULL AND xac_suat IS NULL AND xac_suat_theo_fold IS NULL
        AND chu_y_vung IS NULL AND canh_bao IS NULL));
