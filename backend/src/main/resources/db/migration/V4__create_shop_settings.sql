CREATE TABLE shop_settings (
    id UUID NOT NULL,
    singleton_key BOOLEAN NOT NULL DEFAULT TRUE,
    shop_name VARCHAR(100) NOT NULL,
    region_label VARCHAR(100) NOT NULL,
    business_type VARCHAR(100) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    address VARCHAR(300) NOT NULL,
    opening_time TIME(0) WITHOUT TIME ZONE NOT NULL,
    closing_time TIME(0) WITHOUT TIME ZONE NOT NULL,
    closed_weekday VARCHAR(9),
    parking_available BOOLEAN NOT NULL,
    parking_note VARCHAR(300),
    hero_title VARCHAR(200),
    hero_description VARCHAR(1000),
    groomer_name VARCHAR(100),
    groomer_intro VARCHAR(2000),
    reservation_notice VARCHAR(4000),
    instagram_url VARCHAR(2048),
    naver_blog_url VARCHAR(2048),
    naver_map_url VARCHAR(2048),
    kakao_map_url VARCHAR(2048),
    naver_talktalk_url VARCHAR(2048),
    kakao_channel_url VARCHAR(2048),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT pk_shop_settings PRIMARY KEY (id),
    CONSTRAINT uk_shop_settings_singleton_key UNIQUE (singleton_key),
    CONSTRAINT ck_shop_settings_singleton_key CHECK (singleton_key = TRUE),
    CONSTRAINT ck_shop_settings_shop_name_not_blank CHECK (shop_name ~ '[^[:space:]]'),
    CONSTRAINT ck_shop_settings_region_label_not_blank CHECK (region_label ~ '[^[:space:]]'),
    CONSTRAINT ck_shop_settings_business_type_not_blank CHECK (business_type ~ '[^[:space:]]'),
    CONSTRAINT ck_shop_settings_phone_not_blank CHECK (phone ~ '[^[:space:]]'),
    CONSTRAINT ck_shop_settings_address_not_blank CHECK (address ~ '[^[:space:]]'),
    CONSTRAINT ck_shop_settings_business_hours CHECK (opening_time < closing_time),
    CONSTRAINT ck_shop_settings_closed_weekday CHECK (
        closed_weekday IS NULL OR closed_weekday IN (
            'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
            'FRIDAY', 'SATURDAY', 'SUNDAY'
        )
    ),
    CONSTRAINT fk_shop_settings_created_by
        FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_settings_updated_by
        FOREIGN KEY (updated_by) REFERENCES admin_users(id) ON DELETE RESTRICT
);
