CREATE TABLE breeds (
    id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    description TEXT,
    sort_order INTEGER NOT NULL DEFAULT 100,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT pk_breeds PRIMARY KEY (id),
    CONSTRAINT uk_breeds_slug UNIQUE (slug),
    CONSTRAINT ck_breeds_status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_breeds_name_not_blank CHECK (LENGTH(BTRIM(name)) > 0),
    CONSTRAINT ck_breeds_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_breeds_sort_order CHECK (sort_order >= 0),
    CONSTRAINT fk_breeds_created_by
        FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_breeds_updated_by
        FOREIGN KEY (updated_by) REFERENCES admin_users(id) ON DELETE RESTRICT
);

CREATE TABLE services (
    id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    description TEXT,
    price_text VARCHAR(100),
    sort_order INTEGER NOT NULL DEFAULT 100,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT pk_services PRIMARY KEY (id),
    CONSTRAINT uk_services_slug UNIQUE (slug),
    CONSTRAINT ck_services_status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_services_name_not_blank CHECK (LENGTH(BTRIM(name)) > 0),
    CONSTRAINT ck_services_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_services_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_services_published_fields CHECK (
        status <> 'published'
        OR (
            description IS NOT NULL
            AND LENGTH(BTRIM(description)) > 0
            AND price_text IS NOT NULL
            AND LENGTH(BTRIM(price_text)) > 0
        )
    ),
    CONSTRAINT fk_services_created_by
        FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_services_updated_by
        FOREIGN KEY (updated_by) REFERENCES admin_users(id) ON DELETE RESTRICT
);
