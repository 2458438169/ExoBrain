-- 03 正文索引：索引版本、KB 活动索引状态，以及 chunk_bge_m3 的增量列。
--
-- 执行顺序：必须在 db/jchatmind.sql 与 deploy/migrations/20261014-accounts.sql、20261014-models.sql、
-- 20261016-storage-cleanup.sql 之后执行（本迁移引用 exobrain_model 与 knowledge_base）。
-- 迁移是增量的、可重复执行的：不存在的对象才创建，已存在的列/约束保持不变；
-- 旧数据完全不动，chunk_bge_m3 里 revision_id IS NULL 的行就是 legacy（legacy-title-v1）。
--
-- 应用角色授权（GRANT）由 08 统一处理，本文件不写 GRANT。

BEGIN;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = current_schema() AND table_name = 'exobrain_model') THEN
        RAISE EXCEPTION 'exobrain_model 不存在：请先应用 deploy/migrations/20261014-models.sql 再执行 20261017-03-body-index.sql';
    END IF;
END $$;

-- 一次索引发布记录。DRAFT = 候选已登记；PUBLISHED = 当前活动版本；SUPERSEDED = 被新版本替换；
-- FAILED = 构建失败的尝试（含 FAILED 行也不影响活动版本继续可用）。
CREATE TABLE IF NOT EXISTS exobrain_index_revision (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id            UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    index_version    VARCHAR(32) NOT NULL CHECK (index_version IN ('legacy-title-v1', 'body-v1')),
    status           VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'FAILED')),
    model_catalog_id VARCHAR(64) REFERENCES exobrain_model(id) ON DELETE SET NULL,
    model_name       VARCHAR(200) NOT NULL,
    dimensions       INTEGER NOT NULL CHECK (dimensions = 1024),
    document_count   INTEGER NOT NULL DEFAULT 0 CHECK (document_count >= 0),
    chunk_count      INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    source_set_hash  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at     TIMESTAMPTZ,
    superseded_at    TIMESTAMPTZ,
    CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS exobrain_index_revision_kb_status ON exobrain_index_revision (kb_id, status);

-- 同一个 KB 最多只有一个 PUBLISHED 版本：原子切换在事务内先 SUPERSEDED 旧版本、再 PUBLISHED 新版本。
CREATE UNIQUE INDEX IF NOT EXISTS exobrain_index_revision_active ON exobrain_index_revision (kb_id)
    WHERE status = 'PUBLISHED';

-- KB 当前活动索引。没有状态行的旧库按 IndexVersionPolicy 保持 legacy-title-v1，不会被静默切换。
CREATE TABLE IF NOT EXISTS exobrain_kb_index_state (
    kb_id                UUID PRIMARY KEY REFERENCES knowledge_base(id) ON DELETE CASCADE,
    active_revision_id   UUID REFERENCES exobrain_index_revision(id) ON DELETE RESTRICT,
    active_index_version VARCHAR(32) NOT NULL CHECK (active_index_version IN ('legacy-title-v1', 'body-v1')),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS exobrain_kb_index_state_revision ON exobrain_kb_index_state (active_revision_id);

-- chunk 增量列：旧行为 NULL = legacy，不需要回填，也不需要重写旧向量。
ALTER TABLE chunk_bge_m3 ADD COLUMN IF NOT EXISTS revision_id UUID;
ALTER TABLE chunk_bge_m3 ADD COLUMN IF NOT EXISTS index_version VARCHAR(32);
ALTER TABLE chunk_bge_m3 ADD COLUMN IF NOT EXISTS chunk_ordinal INTEGER;
ALTER TABLE chunk_bge_m3 ADD COLUMN IF NOT EXISTS source_hash TEXT;
ALTER TABLE chunk_bge_m3 ADD COLUMN IF NOT EXISTS start_line INTEGER;
ALTER TABLE chunk_bge_m3 ADD COLUMN IF NOT EXISTS end_line INTEGER;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'exobrain_chunk_revision_fk') THEN
        ALTER TABLE chunk_bge_m3 ADD CONSTRAINT exobrain_chunk_revision_fk
            FOREIGN KEY (revision_id) REFERENCES exobrain_index_revision(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'exobrain_chunk_line_range_ck') THEN
        ALTER TABLE chunk_bge_m3 ADD CONSTRAINT exobrain_chunk_line_range_ck
            CHECK ((start_line IS NULL AND end_line IS NULL)
                OR (start_line >= 1 AND end_line >= start_line));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'exobrain_chunk_index_version_ck') THEN
        ALTER TABLE chunk_bge_m3 ADD CONSTRAINT exobrain_chunk_index_version_ck
            CHECK (index_version IS NULL OR index_version IN ('legacy-title-v1', 'body-v1'));
    END IF;
END $$;

-- 检索与发布都要在 (kb_id, revision_id) 上过滤，因此单独建索引（向量索引仍是基线的 ivfflat）。
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_revision ON chunk_bge_m3 (kb_id, revision_id);

COMMIT;

-- 回滚方式（仅在没有应用在读这些结构时执行；回滚前先确认要回滚的旧应用能否读取新列）：
--   BEGIN;
--   ALTER TABLE chunk_bge_m3 DROP CONSTRAINT IF EXISTS exobrain_chunk_revision_fk;
--   ALTER TABLE chunk_bge_m3 DROP CONSTRAINT IF EXISTS exobrain_chunk_line_range_ck;
--   ALTER TABLE chunk_bge_m3 DROP CONSTRAINT IF EXISTS exobrain_chunk_index_version_ck;
--   DROP INDEX IF EXISTS idx_chunk_bge_m3_revision;
--   ALTER TABLE chunk_bge_m3 DROP COLUMN IF EXISTS revision_id;
--   ALTER TABLE chunk_bge_m3 DROP COLUMN IF EXISTS index_version;
--   ALTER TABLE chunk_bge_m3 DROP COLUMN IF EXISTS chunk_ordinal;
--   ALTER TABLE chunk_bge_m3 DROP COLUMN IF EXISTS source_hash;
--   ALTER TABLE chunk_bge_m3 DROP COLUMN IF EXISTS start_line;
--   ALTER TABLE chunk_bge_m3 DROP COLUMN IF EXISTS end_line;
--   DROP TABLE IF EXISTS exobrain_kb_index_state;
--   DROP TABLE IF EXISTS exobrain_index_revision;
--   COMMIT;
-- 回滚会丢掉版本化索引与来源位置（正文 chunk 行随之删除）；旧代码仍可读取 legacy 行，
-- 但不会有任何 body-v1 结果，也不会伪造位置。