CREATE TABLE admin_webauthn_credentials (
    id UUID PRIMARY KEY,
    admin_user_id UUID NOT NULL,
    credential_id BYTEA NOT NULL,
    credential_type VARCHAR(32) NOT NULL,
    public_key_cose BYTEA NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    uv_initialized BOOLEAN NOT NULL,
    transports VARCHAR(255) NOT NULL DEFAULT '',
    backup_eligible BOOLEAN NOT NULL,
    backup_state BOOLEAN NOT NULL,
    attestation_object BYTEA NOT NULL,
    attestation_client_data_json BYTEA NOT NULL,
    label VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT fk_admin_webauthn_credentials_admin_user
        FOREIGN KEY (admin_user_id) REFERENCES admin_users(id),
    CONSTRAINT uk_admin_webauthn_credentials_credential_id UNIQUE (credential_id),
    CONSTRAINT ck_admin_webauthn_credentials_credential_id_not_empty
        CHECK (OCTET_LENGTH(credential_id) > 0),
    CONSTRAINT ck_admin_webauthn_credentials_public_key_not_empty
        CHECK (OCTET_LENGTH(public_key_cose) > 0),
    CONSTRAINT ck_admin_webauthn_credentials_signature_count
        CHECK (signature_count >= 0),
    CONSTRAINT ck_admin_webauthn_credentials_label_not_blank
        CHECK (label ~ '[^[:space:]]'),
    CONSTRAINT ck_admin_webauthn_credentials_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_admin_webauthn_credentials_revocation
        CHECK ((status = 'ACTIVE' AND revoked_at IS NULL)
            OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
);

CREATE INDEX ix_admin_webauthn_credentials_admin_status
    ON admin_webauthn_credentials (admin_user_id, status);

CREATE TABLE admin_recovery_codes (
    id UUID PRIMARY KEY,
    admin_user_id UUID NOT NULL,
    code_set_id UUID NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP(6) WITH TIME ZONE,
    revoked_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT fk_admin_recovery_codes_admin_user
        FOREIGN KEY (admin_user_id) REFERENCES admin_users(id),
    CONSTRAINT uk_admin_recovery_codes_hash UNIQUE (code_hash),
    CONSTRAINT ck_admin_recovery_codes_hash
        CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_admin_recovery_codes_terminal_state
        CHECK (used_at IS NULL OR revoked_at IS NULL)
);

CREATE INDEX ix_admin_recovery_codes_admin_active
    ON admin_recovery_codes (admin_user_id, code_set_id)
    WHERE used_at IS NULL AND revoked_at IS NULL;
