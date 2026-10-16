-- Durable deletion jobs contain identifiers only, never document content or credentials.
-- Install before starting the knowledge-base privacy/deletion release.
BEGIN;

CREATE TABLE IF NOT EXISTS exobrain_storage_cleanup (
    kind varchar(8) NOT NULL CHECK (kind IN ('KB', 'DOCUMENT')),
    resource_id uuid NOT NULL,
    kb_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (kind, resource_id),
    CHECK (kind <> 'KB' OR resource_id = kb_id)
);

COMMIT;
