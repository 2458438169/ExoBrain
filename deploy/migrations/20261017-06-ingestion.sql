-- 06 持久化文档导入：导入任务表（与 03 的索引版本表、既有文件清理队列互不复用）。
--
-- 执行顺序：db/jchatmind.sql → 20261014-accounts.sql → 20261014-models.sql →
--           20261016-storage-cleanup.sql → 20261017-03-body-index.sql → **20261017-06-ingestion.sql**。
-- 依赖：knowledge_base、document（基线）、exobrain_user / exobrain_resource_acl（账号迁移）、
--       exobrain_index_revision（03）。缺失时给出明确报错，而不是一句外键错误。
-- 迁移是增量的、可重复执行的；不改写任何既有迁移，不写 GRANT（角色授权由任务 08 统一处理）。
--
-- 为什么单独一张表：文件删除队列（exobrain_storage_cleanup）负责“资源已删除后移除文件”，
-- 导入队列负责“发布可用索引”，状态与失败语义不同，混用会让删除任务被 worker 重试重新激活。

BEGIN;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = current_schema() AND table_name = 'document') THEN
        RAISE EXCEPTION 'document 不存在：请先应用 db/jchatmind.sql 再执行 20261017-06-ingestion.sql';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = current_schema() AND table_name = 'exobrain_user') THEN
        RAISE EXCEPTION 'exobrain_user 不存在：请先应用 deploy/migrations/20261014-accounts.sql 再执行 20261017-06-ingestion.sql';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = current_schema() AND table_name = 'exobrain_index_revision') THEN
        RAISE EXCEPTION 'exobrain_index_revision 不存在：请先应用 deploy/migrations/20261017-03-body-index.sql 再执行 20261017-06-ingestion.sql';
    END IF;
END $$;

-- 一条导入任务只携带服务器生成的标识：kb/document 的 UUID、受控相对文件路径、源文件 hash 与 actor。
-- 不保存请求里的原始路径、文件名之外的客户端输入，也不保存正文。
CREATE TABLE IF NOT EXISTS exobrain_ingestion_job (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id             UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    document_id       UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    file_path         TEXT NOT NULL,
    source_hash       TEXT NOT NULL,
    -- 账号被删除时任务行随外键级联消失 = 该导入永久取消（不会复活，也不会继续调用外部模型）。
    actor_user_id     UUID REFERENCES exobrain_user(id) ON DELETE CASCADE,
    status            VARCHAR(16) NOT NULL
                      CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempts          INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts      INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_owner       TEXT,
    lease_expires_at  TIMESTAMPTZ,
    -- 每次领取 +1；所有状态写回都必须带同一个值，过期旧 worker 的晚到结果会被拒绝。
    fencing_token     BIGINT NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    index_revision_id UUID REFERENCES exobrain_index_revision(id) ON DELETE SET NULL,
    chunks_done       INTEGER NOT NULL DEFAULT 0 CHECK (chunks_done >= 0),
    chunks_total      INTEGER CHECK (chunks_total IS NULL OR chunks_total >= 0),
    error_code        VARCHAR(64),
    -- 只允许写安全的固定文案，不写服务商原始异常/堆栈/正文。
    error_message     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    CHECK (status <> 'RUNNING' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL))
);

-- 领取扫描：到期可执行的任务。
CREATE INDEX IF NOT EXISTS exobrain_ingestion_job_claim
    ON exobrain_ingestion_job (status, next_attempt_at);

CREATE INDEX IF NOT EXISTS exobrain_ingestion_job_document
    ON exobrain_ingestion_job (document_id);

-- 同一文档最多一个未完成任务：用户重复点击重试不会产生并行任务或重复 chunk（并发下由唯一索引拒绝）。
CREATE UNIQUE INDEX IF NOT EXISTS exobrain_ingestion_job_active
    ON exobrain_ingestion_job (document_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT');

COMMIT;

-- 回滚（只在没有应用在读这张表时执行）：
--   BEGIN;
--   DROP TABLE IF EXISTS exobrain_ingestion_job;
--   COMMIT;
-- 回滚只丢弃任务状态；已发布的索引版本与 chunk 仍在 exobrain_index_revision / chunk_bge_m3 中，
-- 旧应用可继续读取既有索引，但不会再有任何新的异步导入。
