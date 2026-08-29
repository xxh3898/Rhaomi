CREATE TABLE media_assets (
    id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    source_content_type VARCHAR(32) NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    file_extension VARCHAR(8) NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    source_byte_size BIGINT NOT NULL,
    byte_size BIGINT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT pk_media_assets PRIMARY KEY (id),
    CONSTRAINT uk_media_assets_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_media_assets_status CHECK (status IN ('active', 'archived')),
    CONSTRAINT ck_media_assets_source_content_type CHECK (
        source_content_type IN ('image/jpeg', 'image/png', 'image/heic', 'image/heif')
    ),
    CONSTRAINT ck_media_assets_content_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT ck_media_assets_file_extension CHECK (file_extension IN ('jpg', 'png')),
    CONSTRAINT ck_media_assets_type_consistency CHECK (
        (
            source_content_type = 'image/png'
            AND content_type = 'image/png'
            AND file_extension = 'png'
        )
        OR (
            source_content_type IN ('image/jpeg', 'image/heic', 'image/heif')
            AND content_type = 'image/jpeg'
            AND file_extension = 'jpg'
        )
    ),
    CONSTRAINT ck_media_assets_source_byte_size CHECK (
        source_byte_size > 0 AND source_byte_size <= 20971520
    ),
    CONSTRAINT ck_media_assets_byte_size CHECK (byte_size > 0 AND byte_size <= 31457280),
    CONSTRAINT ck_media_assets_width CHECK (width > 0 AND width <= 12000),
    CONSTRAINT ck_media_assets_height CHECK (height > 0 AND height <= 12000),
    CONSTRAINT ck_media_assets_total_pixels CHECK (
        width::BIGINT * height::BIGINT <= 60000000
    ),
    CONSTRAINT ck_media_assets_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_assets_storage_key CHECK (
        storage_key ~ '^masters/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png)$'
    ),
    CONSTRAINT fk_media_assets_created_by
        FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_media_assets_updated_by
        FOREIGN KEY (updated_by) REFERENCES admin_users(id) ON DELETE RESTRICT
);

CREATE INDEX ix_media_assets_admin_order
    ON media_assets (created_at DESC, id ASC);
