-- Giai đoạn 1: xác thực người dùng

CREATE TABLE users (
    id                    UUID         PRIMARY KEY,
    email                 VARCHAR(255) NOT NULL,
    phone                 VARCHAR(20),
    password_hash         VARCHAR(100) NOT NULL,
    full_name             VARCHAR(150) NOT NULL,
    role                  VARCHAR(20)  NOT NULL
        CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN')),
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    birth_date            DATE,
    gender                VARCHAR(10)
        CONSTRAINT users_gender_check CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    occupation            VARCHAR(150),
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    deleted_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Email/SĐT chỉ cần duy nhất trong các tài khoản chưa bị xoá mềm.
-- Email luôn được lưu ở dạng chữ thường; SĐT ở dạng chuẩn hoá 0xxxxxxxxx.
CREATE UNIQUE INDEX ux_users_email_active ON users (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_users_phone_active ON users (phone) WHERE deleted_at IS NULL AND phone IS NOT NULL;
CREATE INDEX ix_users_role ON users (role);

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE password_reset_otp (
    id                     UUID        PRIMARY KEY,
    user_id                UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    otp_hash               VARCHAR(100) NOT NULL,
    expires_at             TIMESTAMPTZ NOT NULL,
    attempts               INTEGER     NOT NULL DEFAULT 0,
    used                   BOOLEAN     NOT NULL DEFAULT FALSE,
    reset_token_hash       VARCHAR(64),
    reset_token_expires_at TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_password_reset_otp_user ON password_reset_otp (user_id, created_at DESC);
CREATE UNIQUE INDEX ux_password_reset_otp_reset_token ON password_reset_otp (reset_token_hash)
    WHERE reset_token_hash IS NOT NULL;

-- Giai đoạn 2: ảnh X-quang (chưa gắn lượt khám)

CREATE TABLE images (
    id            UUID         PRIMARY KEY,
    owner_id      UUID         NOT NULL REFERENCES users (id),
    storage_key   VARCHAR(100) NOT NULL,        -- tên file ngẫu nhiên trên đĩa, không dùng tên gốc
    content_type  VARCHAR(30)  NOT NULL
        CONSTRAINT images_type_check CHECK (content_type IN ('image/png', 'image/jpeg')),
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes > 0),
    width         INTEGER      NOT NULL CHECK (width > 0),
    height        INTEGER      NOT NULL CHECK (height > 0),
    sha256        VARCHAR(64)  NOT NULL,        -- hash sau khi đã bỏ EXIF
    visit_id      UUID,                         -- khoá ngoại tới visits ở cuối file
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_images_storage_key ON images (storage_key);
CREATE UNIQUE INDEX ux_images_owner_hash  ON images (owner_id, sha256) WHERE deleted_at IS NULL;
CREATE INDEX ix_images_owner ON images (owner_id, created_at DESC) WHERE deleted_at IS NULL;
-- Mỗi lượt khám tối đa một ảnh (áp dụng khi visit_id được dùng)
CREATE UNIQUE INDEX ux_images_visit ON images (visit_id) WHERE visit_id IS NOT NULL AND deleted_at IS NULL;

-- Giai đoạn 3: hồ sơ bệnh nhân (chưa có lượt khám)

CREATE SEQUENCE patient_code_seq START WITH 1;

CREATE TABLE patients (
    id                UUID         PRIMARY KEY,
    code              VARCHAR(20)  NOT NULL,        -- mã hiển thị, dạng BN-000001
    user_id           UUID         REFERENCES users (id),  -- null: hồ sơ do admin tạo, chưa gắn tài khoản
    full_name         VARCHAR(150) NOT NULL,
    birth_date        DATE,
    gender            VARCHAR(10)
        CONSTRAINT patients_gender_check CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    phone             VARCHAR(20),
    occupation        VARCHAR(150),
    workplace         VARCHAR(255),
    exposure_history  TEXT,
    risk_factors      TEXT,
    created_by        UUID         REFERENCES users (id),
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_patients_code ON patients (code);
-- Mỗi tài khoản có tối đa một hồ sơ chưa bị xoá mềm.
CREATE UNIQUE INDEX ux_patients_user_active ON patients (user_id) WHERE user_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_patients_created ON patients (created_at DESC) WHERE deleted_at IS NULL;

-- Giai đoạn 4: lượt khám (bản demo, sẽ chỉnh lại theo từ điển trường chính thức)
-- Tuổi và giới tính không lưu ở đây: lấy từ hồ sơ bệnh nhân.

CREATE TABLE visits (
    id                  UUID         PRIMARY KEY,
    patient_id          UUID         NOT NULL REFERENCES patients (id),
    visit_date          DATE         NOT NULL,   -- mặc định = ngày gửi; created_at = thời điểm gửi
    -- Cần đủ khi dự đoán; khi lưu lịch sử có thể để trống (VD chỉ gửi ảnh)
    industry            VARCHAR(100),            -- ngành (A7 trong dữ liệu huấn luyện)
    years_in_job        NUMERIC(4,1) CHECK (years_in_job >= 0 AND years_in_job <= 60),   -- tuổi nghề
    province            VARCHAR(50),             -- tỉnh (tinh)
    -- Tuỳ chọn
    years_exposed       NUMERIC(4,1) CHECK (years_exposed >= 0 AND years_exposed <= 60),          -- năm phơi nhiễm (nampx)
    height_cm           NUMERIC(5,1) CHECK (height_cm BETWEEN 100 AND 220),
    weight_kg           NUMERIC(5,1) CHECK (weight_kg BETWEEN 20 AND 200),
    systolic_bp         INTEGER      CHECK (systolic_bp BETWEEN 60 AND 260),    -- hatd (cần xác nhận với từ điển nguồn)
    diastolic_bp        INTEGER      CHECK (diastolic_bp BETWEEN 30 AND 160),   -- hatt (cần xác nhận với từ điển nguồn)
    pulse               INTEGER      CHECK (pulse BETWEEN 30 AND 220),
    smoker              BOOLEAN,
    cigarettes_per_day  NUMERIC(4,1) CHECK (cigarettes_per_day >= 0 AND cigarettes_per_day <= 100),
    fvc_l               NUMERIC(4,2) CHECK (fvc_l BETWEEN 0.3 AND 8),
    fev1_l              NUMERIC(4,2) CHECK (fev1_l BETWEEN 0.2 AND 8),
    -- Phần còn lại (đồ bảo hộ, hợp đồng, thể lực, triệu chứng, tiền sử…)
    extra               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    schema_version      INTEGER      NOT NULL DEFAULT 1,
    created_by          UUID         REFERENCES users (id),
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_visits_patient ON visits (patient_id, visit_date DESC) WHERE deleted_at IS NULL;

-- Mỗi lượt khám có tối đa một ảnh: chỉ mục unique ux_images_visit đã có ở trên.
ALTER TABLE images
    ADD CONSTRAINT fk_images_visit FOREIGN KEY (visit_id) REFERENCES visits (id);
