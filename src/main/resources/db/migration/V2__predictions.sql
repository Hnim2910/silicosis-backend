-- Giai đoạn 5: kết quả dự đoán (thử nghiệm) cho ảnh của lượt khám.
-- Mỗi lần gọi service dự đoán ghi một dòng, kể cả khi lỗi (status = FAILED).
-- Kết quả "mới nhất" của lượt khám = dòng mới nhất ứng với ảnh đang gắn.

CREATE TABLE predictions (
    id             UUID             PRIMARY KEY,
    visit_id       UUID             NOT NULL REFERENCES visits (id) ON DELETE CASCADE,
    image_id       UUID             NOT NULL REFERENCES images (id),
    status         VARCHAR(10)      NOT NULL
        CONSTRAINT predictions_status_check CHECK (status IN ('SUCCESS', 'FAILED')),
    probability    DOUBLE PRECISION CHECK (probability >= 0 AND probability <= 1),   -- xác suất lớp "Co"
    label          VARCHAR(10)
        CONSTRAINT predictions_label_check CHECK (label IN ('Co', 'Khong')),
    threshold      DOUBLE PRECISION CHECK (threshold >= 0 AND threshold <= 1),
    model_name     VARCHAR(100),
    model_version  VARCHAR(64),
    error_code     VARCHAR(40),
    created_by     UUID             REFERENCES users (id),
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT predictions_result_check CHECK (
        (status = 'SUCCESS' AND probability IS NOT NULL AND label IS NOT NULL AND threshold IS NOT NULL
            AND model_version IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND error_code IS NOT NULL AND probability IS NULL AND label IS NULL))
);

CREATE INDEX ix_predictions_visit ON predictions (visit_id, created_at DESC);
