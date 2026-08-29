CREATE TABLE notices (
    id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    title VARCHAR(200) NOT NULL,
    slug VARCHAR(160) NOT NULL,
    summary VARCHAR(300),
    body_markdown TEXT,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT pk_notices PRIMARY KEY (id),
    CONSTRAINT uk_notices_slug UNIQUE (slug),
    CONSTRAINT ck_notices_status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_notices_title_not_blank CHECK (LENGTH(BTRIM(title)) > 0),
    CONSTRAINT ck_notices_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_notices_published_fields CHECK (
        status <> 'published'
        OR (
            body_markdown IS NOT NULL
            AND LENGTH(BTRIM(body_markdown)) > 0
            AND published_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_notices_window CHECK (
        expires_at IS NULL
        OR (published_at IS NOT NULL AND expires_at > published_at)
    ),
    CONSTRAINT fk_notices_created_by
        FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_notices_updated_by
        FOREIGN KEY (updated_by) REFERENCES admin_users(id) ON DELETE RESTRICT
);
