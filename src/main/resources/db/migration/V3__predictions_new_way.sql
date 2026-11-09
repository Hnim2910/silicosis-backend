-- Model New Way (nhãn ketqua): dự đoán từ lâm sàng (XGBoost), có phim thì ghép thêm ảnh (BioViL-T 448 x3).
-- CHỈ THÊM / NỚI: không DROP, không xoá dòng. Dòng cũ (model ảnh BioViL-T Set A, nhãn bnn) giữ nguyên với engine = 'LEGACY'.
-- Sao lưu DB trước khi chạy trên DB thật.

-- Dự đoán chỉ từ lâm sàng không có ảnh.
ALTER TABLE predictions ALTER COLUMN image_id DROP NOT NULL;

ALTER TABLE predictions
    ADD COLUMN engine               VARCHAR(20)      NOT NULL DEFAULT 'LEGACY'
        CONSTRAINT predictions_engine_check CHECK (engine IN ('LEGACY', 'NEW_WAY')),
    ADD COLUMN p_lam_sang           DOUBLE PRECISION CHECK (p_lam_sang >= 0 AND p_lam_sang <= 1),
    ADD COLUMN nguong_lam_sang      DOUBLE PRECISION CHECK (nguong_lam_sang >= 0 AND nguong_lam_sang <= 1),
    ADD COLUMN bao_nguy_co_lam_sang BOOLEAN,
    ADD COLUMN co_anh               BOOLEAN,
    ADD COLUMN p_anh                DOUBLE PRECISION CHECK (p_anh >= 0 AND p_anh <= 1),
    ADD COLUMN p_ghep               DOUBLE PRECISION CHECK (p_ghep >= 0 AND p_ghep <= 1),
    ADD COLUMN nguong_ghep          DOUBLE PRECISION CHECK (nguong_ghep >= 0 AND nguong_ghep <= 1),
    ADD COLUMN bao_nguy_co_ghep     BOOLEAN,
    ADD COLUMN ket_luan_cuoi        BOOLEAN;

-- Với NEW_WAY thành công: probability/threshold/label (cột cũ) = điểm cuối cùng (p_ghep nếu có ảnh, ngược lại
-- p_lam_sang), ngưỡng tương ứng, label 'Co' khi ket_luan_cuoi. Nhờ vậy ràng buộc predictions_result_check cũ vẫn đúng.
ALTER TABLE predictions ADD CONSTRAINT predictions_new_way_check CHECK (
    engine <> 'NEW_WAY' OR status <> 'SUCCESS' OR (
        p_lam_sang IS NOT NULL AND nguong_lam_sang IS NOT NULL AND bao_nguy_co_lam_sang IS NOT NULL
        AND co_anh IS NOT NULL AND ket_luan_cuoi IS NOT NULL
        AND (co_anh = FALSE OR (image_id IS NOT NULL AND p_anh IS NOT NULL AND p_ghep IS NOT NULL
                                AND nguong_ghep IS NOT NULL AND bao_nguy_co_ghep IS NOT NULL))));
