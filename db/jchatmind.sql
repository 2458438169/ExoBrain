-- ============================================================
-- JChatMind 主业务库建表脚本
-- 来源：教程「Agent开发实践（三）：数据模型设计」的 DDL
--      + 对照 6 个 mapper XML 校正
-- 执行：docker exec -i jchatmind-postgres psql -U postgres -d jchatmind -v ON_ERROR_STOP=1 < db/jchatmind.sql
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- agent：把 Agent 本身从代码里抽出来，变成一条数据记录
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT NOT NULL,
    description   TEXT,
    system_prompt TEXT,
    model         TEXT,                     -- 取值必须是 "deepseek-chat" / "glm-4.6"
                                            -- 与 MultiChatClientConfig 的 @Bean 名、AgentDTO.ModelType 一致
    allowed_tools JSONB,                    -- 可选工具列表，如 ["fileSystemTool"]
    allowed_kbs   JSONB,                    -- 允许访问的知识库 id 列表
    chat_options  JSONB,                    -- {"temperature":0.7,"topP":1.0,"messageLength":10}
    created_at    TIMESTAMP DEFAULT NOW(),
    updated_at    TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- chat_session：一次对话的锚点，绑定到某个 Agent
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_session (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id   UUID REFERENCES agent(id) ON DELETE SET NULL,
    title      TEXT,
    metadata   JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- chat_message：所有语义片段统一抽象为 message，靠 role 区分
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_message (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role       TEXT NOT NULL,               -- user / assistant / system / tool
    content    TEXT,
    metadata   JSONB,                       -- 工具调用、RAG 片段、模型参数
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_message_session ON chat_message (session_id, created_at);

-- ------------------------------------------------------------
-- knowledge_base：系统私有知识，划清「模型本来就知道」与「系统必须额外提供」的边界
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_base (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    description TEXT,
    metadata    JSONB,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- document：原始资料这一层
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id      UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    filename   TEXT NOT NULL,
    filetype   TEXT,                        -- md / pdf / txt
    size       BIGINT,
    metadata   JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_document_kb ON document (kb_id);

-- ------------------------------------------------------------
-- chunk_bge_m3：切片 + 向量
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chunk_bge_m3 (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id      UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    doc_id     UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content    TEXT NOT NULL,               -- 向量只负责「找」，文本才负责「看」
    -- ⚠ 教程写的是 JSONB，但 ChunkBgeM3Mapper.xml 的 insert 是裸 #{metadata}，
    --   没有 CAST(... AS jsonb)，写成 JSONB 会在插入时报
    --   "column metadata is of type jsonb but expression is of type character varying"
    --   故此处按 TEXT 建（其余 5 张表的 metadata 都是 JSONB 且 mapper 中有 CAST）
    metadata   TEXT,
    embedding  VECTOR(1024) NOT NULL,       -- bge-m3 是 1024 维
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_kb ON chunk_bge_m3 (kb_id);

-- similaritySearch 用的是 `<->`（L2 距离），索引算子类必须匹配 vector_l2_ops
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_embedding
    ON chunk_bge_m3 USING ivfflat (embedding vector_l2_ops) WITH (lists = 100);

-- ------------------------------------------------------------
-- 种子数据：一个可用的默认 Agent
-- 工具分三类（对照代码核实）：
--   FIXED  自动带上、无需声明：KnowledgeTool(KnowledgeTools)、terminate(TerminateTool)
--                             以及 tools/test 下的 weatherTool / cityTool / dateTool（都是 @Component，是活的固定工具）
--   OPTIONAL 需在此声明：dataBaseTool、emailTool
--   DISABLED @Component 被注释掉，不是 Bean：fileSystemTool(FileSystemTools)、directAnswer(DirectAnswerTool)
-- 注：resolveRuntimeTools() 对不存在的工具名是静默跳过，写错不会报错、只会少一个工具
-- ------------------------------------------------------------
INSERT INTO agent (name, description, system_prompt, model, allowed_tools, allowed_kbs, chat_options)
VALUES (
    '默认助手',
    'JChatMind 默认 Agent：可以查询时间、城市、天气与数据库',
    '你是一个智能助手。请根据用户的请求决定下一步动作，需要外部信息时调用相应工具。',
    'deepseek-chat',
    '["dataBaseTool"]'::jsonb,
    '[]'::jsonb,
    '{"temperature":0.7,"topP":1.0,"messageLength":10}'::jsonb
);
