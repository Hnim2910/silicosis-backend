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
    visit_id      UUID,                         -- để trống; sẽ thêm khoá ngoại tới visits khi làm lượt khám
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_images_storage_key ON images (storage_key);
CREATE UNIQUE INDEX ux_images_owner_hash  ON images (owner_id, sha256) WHERE deleted_at IS NULL;
CREATE INDEX ix_images_owner ON images (owner_id, created_at DESC) WHERE deleted_at IS NULL;
-- Mỗi lượt khám tối đa một ảnh (áp dụng khi visit_id được dùng)
CREATE UNIQUE INDEX ux_images_visit ON images (visit_id) WHERE visit_id IS NOT NULL AND deleted_at IS NULL;
