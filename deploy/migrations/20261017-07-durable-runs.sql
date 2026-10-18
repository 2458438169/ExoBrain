-- 07 持久化对话任务：提交事务里的排队任务（exobrain_chat_run_job）。
--
-- 执行顺序：db/jchatmind.sql → deploy/migrations/20261014-accounts.sql → 20261014-models.sql →
-- 20261016-storage-cleanup.sql → 20261017-03-body-index.sql → 20261017-05-runs.sql → 本文件。
-- （06 的 20261017-06-ingestion.sql 与本文件互不依赖，按编号顺序执行即可。）
-- 迁移是增量、可重复执行的：只创建尚不存在对象，不改写既有迁移。应用角色授权（GRANT）由 08 统一处理。
--
-- 这张表是“对话执行任务”的唯一可靠来源：SSE / JVM 事件只是唤醒提示，进程重启后由本表恢复 QUEUED。
-- 它与 exobrain_storage_cleanup（文件删除）和 exobrain_ingestion_job（索引导入）是三张独立的队列。

BEGIN;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = current_schema() AND table_name = 'exobrain_run') THEN
        RAISE EXCEPTION 'exobrain_run 不存在：请先安装 deploy/migrations/20261017-05-runs.sql';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = current_schema() AND table_name = 'chat_session') THEN
        RAISE EXCEPTION 'chat_session 不存在：请先安装 db/jchatmind.sql';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = current_schema() AND table_name = 'exobrain_user') THEN
        RAISE EXCEPTION 'exobrain_user 不存在：请先安装 deploy/migrations/20261014-accounts.sql';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS exobrain_chat_run_job (
    id                     UUID PRIMARY KEY,
    -- 与 05 的运行摘要一一对应：排队任务不另建一套运行语义。
    run_id                 UUID NOT NULL UNIQUE REFERENCES exobrain_run(id) ON DELETE CASCADE,
    origin                 VARCHAR(16) NOT NULL CHECK (origin IN ('ACCOUNT', 'DEMO')),
    session_id             UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    triggering_message_id  UUID REFERENCES chat_message(id) ON DELETE SET NULL,
    agent_id               UUID REFERENCES agent(id) ON DELETE SET NULL,
    -- 幂等作用域：ACCOUNT:<userId> 或 DEMO:<访客 Cookie 的 SHA-256>。原始 Cookie 不落库。
    actor_scope            VARCHAR(96) NOT NULL,
    actor_user_id          UUID REFERENCES exobrain_user(id) ON DELETE CASCADE,
    -- 提交时的服务端会话校验信息（账号）：版本号 + 会话令牌哈希，只在服务端用于重新授权。
    actor_auth_version     INTEGER,
    actor_session_hash     VARCHAR(128),
    -- 游客只保存 owner 哈希（chat_session.metadata.demoOwner 的同一值），不保存原始 Cookie。
    demo_owner             VARCHAR(64),
    idempotency_key        VARCHAR(128) NOT NULL,
    request_digest         VARCHAR(64) NOT NULL,
    -- 固定的提交快照：排队期间不改用别的正文或历史。
    user_input             TEXT NOT NULL,
    history_snapshot       TEXT NOT NULL,
    status                 VARCHAR(16) NOT NULL
                           CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    attempts               INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts           INTEGER NOT NULL DEFAULT 1 CHECK (max_attempts >= 1),
    cancel_requested       BOOLEAN NOT NULL DEFAULT FALSE,
    lease_owner            VARCHAR(200),
    lease_expires_at       TIMESTAMPTZ,
    fencing_token          BIGINT NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    error_code             VARCHAR(64),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    -- 执行中的任务必须带租约；没有租约的 RUNNING 无法判断“谁还活着”。
    CHECK (status <> 'RUNNING' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CHECK (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED') OR finished_at IS NOT NULL),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    -- 游客任务没有账号身份；账号任务必须有账号身份。两者都不缺也不会同时出现。
    CHECK ((origin = 'ACCOUNT' AND actor_user_id IS NOT NULL AND demo_owner IS NULL)
        OR (origin = 'DEMO' AND actor_user_id IS NULL AND demo_owner IS NOT NULL))
);

-- 幂等键：(actor, session, key) 唯一。重试同一意图只会命中原来那一行，不按问题文本去重。
CREATE UNIQUE INDEX IF NOT EXISTS exobrain_chat_run_job_idempotency
    ON exobrain_chat_run_job (actor_scope, session_id, idempotency_key);

-- 同一会话最多一个活动运行：由数据库保证，而不是进程内 Set。
CREATE UNIQUE INDEX IF NOT EXISTS exobrain_chat_run_job_active
    ON exobrain_chat_run_job (session_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX IF NOT EXISTS exobrain_chat_run_job_queue
    ON exobrain_chat_run_job (status, created_at);

CREATE INDEX IF NOT EXISTS exobrain_chat_run_job_actor
    ON exobrain_chat_run_job (actor_scope, status);

COMMIT;

-- 回滚（回滚前先停掉 worker 领取，否则新应用会找不到表）：
--   BEGIN;
--   DROP TABLE IF EXISTS exobrain_chat_run_job;
--   COMMIT;
-- 回滚只丢排队任务与幂等键；已经写入的消息、运行摘要与事件保留，便于诊断。
