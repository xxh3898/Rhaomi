CREATE TABLE content_revision_state (
    singleton_key SMALLINT NOT NULL,
    content_revision BIGINT NOT NULL,
    CONSTRAINT pk_content_revision_state PRIMARY KEY (singleton_key),
    CONSTRAINT ck_content_revision_state_singleton CHECK (singleton_key = 1),
    CONSTRAINT ck_content_revision_state_revision CHECK (content_revision >= 0)
);

INSERT INTO content_revision_state (singleton_key, content_revision)
VALUES (1, 0);

CREATE TABLE publishing_outbox (
    id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id UUID NOT NULL,
    content_revision BIGINT NOT NULL,
    available_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expected_boundary_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_publishing_outbox PRIMARY KEY (id),
    CONSTRAINT ck_publishing_outbox_kind CHECK (
        kind IN (
            'CONTENT_CHANGED',
            'NOTICE_PUBLISHED_AT_DUE',
            'NOTICE_EXPIRES_AT_DUE',
            'GALLERY_PUBLISHED_AT_DUE'
        )
    ),
    CONSTRAINT ck_publishing_outbox_source_type CHECK (
        source_type IN (
            'SHOP_SETTINGS',
            'BREED',
            'SERVICE',
            'NOTICE',
            'GALLERY_ITEM',
            'MEDIA_ASSET'
        )
    ),
    CONSTRAINT ck_publishing_outbox_revision CHECK (content_revision > 0),
    CONSTRAINT ck_publishing_outbox_boundary CHECK (
        (
            kind = 'CONTENT_CHANGED'
            AND expected_boundary_at IS NULL
        )
        OR (
            kind IN (
                'NOTICE_PUBLISHED_AT_DUE',
                'NOTICE_EXPIRES_AT_DUE',
                'GALLERY_PUBLISHED_AT_DUE'
            )
            AND expected_boundary_at IS NOT NULL
            AND available_at = expected_boundary_at
        )
    ),
    CONSTRAINT ck_publishing_outbox_source_kind CHECK (
        kind = 'CONTENT_CHANGED'
        OR (
            kind IN ('NOTICE_PUBLISHED_AT_DUE', 'NOTICE_EXPIRES_AT_DUE')
            AND source_type = 'NOTICE'
        )
        OR (
            kind = 'GALLERY_PUBLISHED_AT_DUE'
            AND source_type = 'GALLERY_ITEM'
        )
    )
);

CREATE INDEX ix_publishing_outbox_available
    ON publishing_outbox (available_at, id);

CREATE INDEX ix_publishing_outbox_source
    ON publishing_outbox (source_type, source_id, available_at);

CREATE INDEX ix_publishing_outbox_content_revision
    ON publishing_outbox (content_revision);
