CREATE TABLE gallery_items (
    id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    dog_name VARCHAR(100),
    breed_id UUID,
    primary_service_id UUID,
    cover_image_id UUID,
    before_image_id UUID,
    after_image_id UUID,
    summary VARCHAR(1000),
    alt_text VARCHAR(300),
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 100,
    performed_at TIMESTAMP(6) WITH TIME ZONE,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT pk_gallery_items PRIMARY KEY (id),
    CONSTRAINT ck_gallery_items_status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_gallery_items_dog_name_not_blank CHECK (
        dog_name IS NULL OR dog_name ~ '[^[:space:]]'
    ),
    CONSTRAINT ck_gallery_items_summary_not_blank CHECK (
        summary IS NULL OR summary ~ '[^[:space:]]'
    ),
    CONSTRAINT ck_gallery_items_alt_text_not_blank CHECK (
        alt_text IS NULL OR alt_text ~ '[^[:space:]]'
    ),
    CONSTRAINT ck_gallery_items_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_gallery_items_published_fields CHECK (
        status <> 'published'
        OR (
            breed_id IS NOT NULL
            AND primary_service_id IS NOT NULL
            AND cover_image_id IS NOT NULL
            AND alt_text IS NOT NULL
            AND alt_text ~ '[^[:space:]]'
            AND published_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_gallery_items_before_after_distinct CHECK (
        before_image_id IS NULL
        OR after_image_id IS NULL
        OR before_image_id <> after_image_id
    ),
    CONSTRAINT fk_gallery_items_breed
        FOREIGN KEY (breed_id) REFERENCES breeds(id) ON DELETE RESTRICT,
    CONSTRAINT fk_gallery_items_primary_service
        FOREIGN KEY (primary_service_id) REFERENCES services(id) ON DELETE RESTRICT,
    CONSTRAINT fk_gallery_items_cover_image
        FOREIGN KEY (cover_image_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_gallery_items_before_image
        FOREIGN KEY (before_image_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_gallery_items_after_image
        FOREIGN KEY (after_image_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_gallery_items_created_by
        FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_gallery_items_updated_by
        FOREIGN KEY (updated_by) REFERENCES admin_users(id) ON DELETE RESTRICT
);

CREATE INDEX ix_gallery_items_admin_order
    ON gallery_items (featured DESC, sort_order ASC, published_at DESC NULLS LAST, id ASC);
