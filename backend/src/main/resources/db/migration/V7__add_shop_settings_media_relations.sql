ALTER TABLE shop_settings
    ADD COLUMN hero_image_id UUID,
    ADD COLUMN hero_image_alt_text VARCHAR(300),
    ADD COLUMN groomer_image_id UUID,
    ADD COLUMN groomer_image_alt_text VARCHAR(300),
    ADD COLUMN og_image_id UUID,
    ADD CONSTRAINT fk_shop_settings_hero_image
        FOREIGN KEY (hero_image_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_shop_settings_groomer_image
        FOREIGN KEY (groomer_image_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_shop_settings_og_image
        FOREIGN KEY (og_image_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_shop_settings_hero_image_alt_pair CHECK (
        (hero_image_id IS NULL AND hero_image_alt_text IS NULL)
        OR (
            hero_image_id IS NOT NULL
            AND hero_image_alt_text IS NOT NULL
            AND hero_image_alt_text ~ '[^[:space:]]'
        )
    ),
    ADD CONSTRAINT ck_shop_settings_groomer_image_alt_pair CHECK (
        (groomer_image_id IS NULL AND groomer_image_alt_text IS NULL)
        OR (
            groomer_image_id IS NOT NULL
            AND groomer_image_alt_text IS NOT NULL
            AND groomer_image_alt_text ~ '[^[:space:]]'
        )
    );
