-- Run with a database administrator before starting the model-catalog release.
-- This migration stores no provider credentials. Initial secrets are imported once
-- by the application and encrypted with the separately mounted AES master key.
BEGIN;

CREATE TABLE IF NOT EXISTS exobrain_model (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('LLM', 'EMBEDDING')),
    protocol VARCHAR(16) NOT NULL CHECK (protocol IN ('deepseek', 'zhipuai', 'openai', 'ollama')),
    base_url VARCHAR(2048) NOT NULL,
    provider_model VARCHAR(200) NOT NULL,
    encrypted_api_key TEXT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_model BOOLEAN NOT NULL DEFAULT FALSE,
    dimensions INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (NOT default_model OR enabled),
    CHECK ((kind = 'LLM' AND dimensions IS NULL AND protocol <> 'ollama')
        OR (kind = 'EMBEDDING' AND dimensions = 1024 AND protocol IN ('openai', 'ollama')))
);
CREATE UNIQUE INDEX IF NOT EXISTS exobrain_one_default_model_per_kind
    ON exobrain_model (kind) WHERE default_model;

CREATE TABLE IF NOT EXISTS exobrain_model_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    bootstrapped BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO exobrain_model_state (id) VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS exobrain_kb_embedding (
    kb_id UUID PRIMARY KEY REFERENCES knowledge_base(id) ON DELETE CASCADE,
    model_id VARCHAR(64) NOT NULL REFERENCES exobrain_model(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS exobrain_kb_embedding_model ON exobrain_kb_embedding (model_id);

-- NOT VALID preserves any historical unknown model strings, while new writes and
-- deletion of referenced catalog models are protected by the foreign key.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'exobrain_agent_model_fk') THEN
        ALTER TABLE agent ADD CONSTRAINT exobrain_agent_model_fk
            FOREIGN KEY (model) REFERENCES exobrain_model(id) ON DELETE RESTRICT NOT VALID;
    END IF;
END $$;

COMMIT;
