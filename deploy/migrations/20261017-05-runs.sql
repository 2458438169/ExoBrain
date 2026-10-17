-- 05 执行追踪：运行摘要（exobrain_run）与顺序事件（exobrain_run_event）。
--
-- 执行顺序：db/jchatmind.sql → deploy/migrations/20261014-accounts.sql → 20261014-models.sql →
-- 20261016-storage-cleanup.sql → 20261017-03-body-index.sql → 本文件。
-- 迁移是增量、可重复执行的：只创建尚不存在对象，不修改既有迁移，不回填旧数据。
-- 旧对话没有运行记录时保持“没有记录”，不补造。应用角色授权（GRANT）由 08 统一处理，本文件不写 GRANT。
-- 事件保留/清理策略不在本迁移里自动执行：见 docs/adr/0005-run-tracing.md 与 deliveries/05.md。

BEGIN;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = current_schema() AND table_name = 'chat_session') THEN
        RAISE EXCEPTION 'chat_session 不存在：请先安装 db/jchatmind.sql';
    END IF;
END $$;

-- 一次运行的摘要。名称与配置哈希都是“运行开始时”的值，后续改名/改配置不会把历史运行解释成另一份配置。
CREATE TABLE IF NOT EXISTS exobrain_run (
    id                     UUID PRIMARY KEY,
    schema_version         SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    session_id             UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    triggering_message_id  UUID REFERENCES chat_message(id) ON DELETE SET NULL,
    agent_id               UUID REFERENCES agent(id) ON DELETE SET NULL,
    agent_name_at_start    VARCHAR(120),
    agent_config_hash      VARCHAR(64),
    model_catalog_id       VARCHAR(64),
    model_name_at_start    VARCHAR(200),
    status                 VARCHAR(16) NOT NULL
                           CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    origin                 VARCHAR(16) NOT NULL CHECK (origin IN ('ACCOUNT', 'DEMO')),
    submitted_at           TIMESTAMPTZ NOT NULL,
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    duration_ms            BIGINT CHECK (duration_ms IS NULL OR duration_ms >= 0),
    queue_duration_ms      BIGINT CHECK (queue_duration_ms IS NULL OR queue_duration_ms >= 0),
    input_tokens           INTEGER CHECK (input_tokens IS NULL OR input_tokens >= 0),
    output_tokens          INTEGER CHECK (output_tokens IS NULL OR output_tokens >= 0),
    total_tokens           INTEGER CHECK (total_tokens IS NULL OR total_tokens >= 0),
    error_code             VARCHAR(64),
    answer_message_id      UUID REFERENCES chat_message(id) ON DELETE SET NULL,
    trace_incomplete       BOOLEAN NOT NULL DEFAULT FALSE,
    heartbeat_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- 终态必须有结束时间；未知用量保持 NULL，不用 0 冒充。
    CHECK (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED') OR finished_at IS NOT NULL),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX IF NOT EXISTS exobrain_run_session ON exobrain_run (session_id, submitted_at);
CREATE INDEX IF NOT EXISTS exobrain_run_status ON exobrain_run (status);

-- 顺序事件：一个事件只在真实结束时写入一行（没有“开始中”的悬空行）。
-- (run_id, sequence) 唯一，重复写入同一事件会被拒绝，不产生两条时间线。
CREATE TABLE IF NOT EXISTS exobrain_run_event (
    id             UUID PRIMARY KEY,
    run_id         UUID NOT NULL REFERENCES exobrain_run(id) ON DELETE CASCADE,
    sequence       INTEGER NOT NULL CHECK (sequence >= 1),
    type           VARCHAR(32) NOT NULL
                   CHECK (type IN ('MODEL_CALL', 'MODEL_RETRY', 'TOOL_CALL', 'RETRIEVAL',
                                   'PERSIST_MESSAGE', 'STEP_LIMIT', 'ERROR')),
    status         VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    started_at     TIMESTAMPTZ NOT NULL,
    duration_ms    BIGINT NOT NULL CHECK (duration_ms >= 0),
    tool_call_id   VARCHAR(128),
    tool_name      VARCHAR(100),
    retrieval_id   VARCHAR(128),
    retry_attempt  INTEGER CHECK (retry_attempt IS NULL OR retry_attempt >= 1),
    error_code     VARCHAR(64),
    detail         VARCHAR(200),
    CONSTRAINT exobrain_run_event_sequence UNIQUE (run_id, sequence)
);

COMMIT;

-- 回滚方式（仅在确认没有应用在读写这两张表时执行；回滚会丢掉执行详情，对话与消息不受影响）：
--   BEGIN;
--   DROP TABLE IF EXISTS exobrain_run_event;
--   DROP TABLE IF EXISTS exobrain_run;
--   COMMIT;