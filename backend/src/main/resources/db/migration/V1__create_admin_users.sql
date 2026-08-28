CREATE TABLE admin_users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_admin_users_email UNIQUE (email),
    CONSTRAINT ck_admin_users_email_normalized CHECK (email = LOWER(email)),
    CONSTRAINT ck_admin_users_password_hash_not_blank CHECK (LENGTH(password_hash) > 0),
    CONSTRAINT ck_admin_users_role CHECK (role = 'ADMIN')
);
