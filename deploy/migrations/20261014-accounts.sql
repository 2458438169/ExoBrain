-- Apply as the database owner before starting the account-enabled application.
-- Existing application records and public examples are retained without claiming ownership.
BEGIN;

CREATE TABLE IF NOT EXISTS exobrain_user (
    id uuid PRIMARY KEY,
    username varchar(32) NOT NULL UNIQUE CHECK (username = lower(username)),
    display_name varchar(64) NOT NULL,
    role varchar(8) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled boolean NOT NULL DEFAULT true,
    password_hash text NOT NULL,
    auth_version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS exobrain_login_session (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES exobrain_user(id) ON DELETE CASCADE,
    csrf_token varchar(43) NOT NULL,
    auth_version integer NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS exobrain_login_session_user_idx ON exobrain_login_session(user_id);
CREATE INDEX IF NOT EXISTS exobrain_login_session_expiry_idx ON exobrain_login_session(expires_at);

CREATE TABLE IF NOT EXISTS exobrain_resource_acl (
    resource_type varchar(8) NOT NULL CHECK (resource_type IN ('AGENT', 'KB', 'SESSION')),
    resource_id uuid NOT NULL,
    owner_id uuid NOT NULL REFERENCES exobrain_user(id),
    created_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY(resource_type, resource_id)
);
CREATE INDEX IF NOT EXISTS exobrain_resource_acl_owner_idx ON exobrain_resource_acl(owner_id, resource_type);

COMMIT;
