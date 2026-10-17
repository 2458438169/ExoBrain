# ADR 0006：持久化文档导入任务、租约/fencing 与登出后的导入策略

- 状态：已接受（任务 06，2026-10-17）
- 相关：[ADR 0003](0003-body-chunking-and-index-versioning.md)（索引版本与原子发布）、[共享契约 §6](../development/contracts.md)
- 实施：[deliveries/06.md](../development/deliveries/06.md)；迁移 `deploy/migrations/20261017-06-ingestion.sql`

## 背景

任务 03 完成正文切块与版本化发布后，上传路径仍然是“文档记录 + 文件 + 候选索引 + Embedding + 发布”在同一个
HTTP 事务里完成（见 deliveries/03.md 第 9 节限制 1–2）。这带来三个问题：

1. 上传响应时间取决于外部 Embedding 服务，超时或失败需要用户整份重传；
2. 事务里等待外部服务会长时间持有 KB 行锁与文档行，阻塞其他上传与删除；
3. 进程在“文件已写、事务未提交”之间崩溃会留下没有记录的文件，失败也无法在后台恢复。

本 ADR 决定：把“文件接收 + 文档记录 + 导入任务”做成一个短事务，索引由持久化 worker 异步完成，
并把并发、重试与删除竞态显式写入数据库约束。

## 决定

### 1. 任务状态与“成功”的含义

`QUEUED → RUNNING → SUCCEEDED`；临时失败进入 `RETRY_WAIT` 后按有上限的退避重试，超过尝试上限
`FAILED`；资源删除或权限撤销 `CANCELLED`。

**任务成功 = 可用索引已原子发布**（03 的 `exobrain_index_revision` 出现新的 `PUBLISHED` 版本并写入
`exobrain_kb_index_state`）。上传返回 200 只表示“已受理”，文件接收成功不等于可检索。界面据此区分
“等待处理 / 正在索引 / 可检索 / 处理失败”，不使用百分比。

### 2. 队列表独立、与文件清理队列不混用

新表 `exobrain_ingestion_job` 只携带服务器生成的标识：`kb_id`、`document_id`、受控相对 `file_path`、
源文本 hash、`actor_user_id`，以及 `status/attempts/max_attempts/next_attempt_at/lease_owner/
lease_expires_at/fencing_token/chunks_done/chunks_total/error_code/error_message`。

- 不复用 `exobrain_storage_cleanup`：后者负责“资源删除后移除文件”，前者负责“发布索引”，状态与失败语义不同。
- `document_id`、`kb_id`、`actor_user_id` 都是 `ON DELETE CASCADE`：资源或账号被删除时任务行随之消失，
  不能被重试“复活”，也不会有后台线程继续为已删除资源调用外部模型。

### 3. 领取用短语句 + 租约 + fencing

领取是一条语句（`FOR UPDATE SKIP LOCKED` + `UPDATE ... RETURNING`），只取到期的 `QUEUED`/`RETRY_WAIT`
或租约已过期的 `RUNNING`。每次领取 `attempts + 1`、`fencing_token + 1`、写入 `lease_owner` 与
`lease_expires_at`。

- 所有状态写回（续租、成功、失败、重试）都带 `fencing_token` 与 `status='RUNNING'` 条件：
  租约过期后被重新领取的旧 worker 即使晚收到模型响应，**一个状态也写不回去**。
- 发布闸门（`IngestionPublisher`）在**短事务内**先 `SELECT ... FOR UPDATE` 锁定任务行，核对
  `status/fencing_token/lease_owner/租约未过期`，再重新检查账号状态、KB 归属、文档存在与文件 hash，
  最后调用 03 的 `RetrievalIndexService.publish`。发布被拒绝时事务整体回滚，旧索引保持可用。
- worker 每完成一个 chunk 续租一次并写入真实进度；续租失败即停止，不再尝试发布。

### 4. 外部调用不进入数据库事务

解析、切块与 Embedding 全部在事务之外；发布事务里只有数据库读写与 03 的原子切换。
因此**重复执行可能重复调用向量服务并产生额外费用**：任务崩溃后会被重新领取，已发生的调用不会回滚。
本方案只保证“最终发布不产生重复 chunk”（版本隔离 + 唯一约束 + 每次发布新建 revision），
**不声称外部调用 exactly-once**。

### 5. 有限重试与终态

- 可重试：`EMBEDDING_UNAVAILABLE`（向量服务 502/网络）、`INDEX_CONFLICT`（候选文档集合或基础版本过期，
  重建候选即可）、`FILE_UNREADABLE`、`PUBLISH_FAILED`。
- 不重试（`FAILED`）：`EMBEDDING_REJECTED`（400/格式/维度）、`MODEL_UNAVAILABLE`/`MODEL_DISABLED`、
  `NOT_INDEXABLE`、`TOO_MANY_CHUNKS`、`FILE_CHANGED`、`FILE_TOO_LARGE`、`UNSUPPORTED_TYPE`、
  `ATTEMPTS_EXHAUSTED`。
- 直接取消（`CANCELLED`）：`DOCUMENT_DELETED`、`KNOWLEDGE_BASE_DELETED`、`ACCOUNT_DISABLED`、
  `NOT_AUTHORIZED`。
- 退避：第 n 次失败等待 `min(base * 2^(n-1), max)`，默认 30s 起、600s 封顶、3 次尝试。
- 上限：并发任务数（默认 2/进程）、文件大小（默认 5 MiB）、单文档 chunk 数（默认 500）、
  每次 Embedding 请求的超时仍由 `ModelRuntimeService` 控制（60s 上限）。

### 6. 文件不参与数据库事务：补偿 + 孤儿清理

上传在事务内先插入文档记录，再写文件（本地、受控路径 `kbId/documentId/随机名.md`），最后插入任务行。
文件操作不在数据库事务里，因此：

- 事务回滚时用 `afterCompletion` 补偿删除（快速路径）；
- 进程在“文件已写、事务未提交”之间崩溃时，`IngestionOrphanFileSweeper` 兜底：只删除同时满足
  “两级目录名都是规范 UUID + KB 仍存在 + 没有 `document` 行 + 没有任何导入任务引用 + 目录 mtime 早于宽限期
  （默认 60 分钟）”的目录，并在删除时逐层校验真实路径、拒绝符号链接与目录联接。
  KB 级目录与非 UUID 名字一律跳过（KB 级清理仍属于 `StorageCleanupService`），因此不会误删他人文件。

### 7. 删除与并发：由约束与版本检查保证顺序，而不是“开始时检查一次”

- `document`/`knowledge_base` 删除会级联删除任务行；worker 的续租、发布闸门与发布事务都重新检查资源存在，
  所以“构建中删除”不会发布任何结果，也不会复活已删资源。
- 03 的发布事务再核对一次候选文档集合、基础版本、模型绑定，并在 `knowledge_base` 行上加锁；
  新上传导致的候选过期会让本次发布被拒绝（`INDEX_CONFLICT`，重试后重建候选）。
- chunk 对 `document` 的外键是级联删除：即使删除恰好发生在发布提交前后，也不会留下可检索的孤儿索引。
- 文档列表 DTO 增量返回 `jobId/indexStatus/chunksDone/chunksTotal`，删除后的文档直接从列表消失。

### 8. 登录、登出与账号状态

导入任务按**资源所有权与账号有效性**继续，而不是按会话撤销（与聊天运行按会话撤销的规则不同）：

- **用户退出登录（会话被撤销）不影响已受理的导入**：任务里保存的是 `actor_user_id` 与资源 id，
  不依赖 Cookie/会话；公开或私有 KB 的导入都会继续到终态。
- **账号停用**：worker 开始前与发布前的复核发现 `exobrain_user.enabled = false` → `CANCELLED`
  （`ACCOUNT_DISABLED`），不发布任何索引。
- **账号删除**：`actor_user_id` 外键级联删除任务行 → 导入永久取消。
- **KB 归属变化**：只有当账号仍拥有该 KB（或管理员对公开 KB 的既有权限）时才继续；否则
  `NOT_AUTHORIZED` → `CANCELLED`。后台线程不读取 `AuthContext`，也不冒用管理员身份。

例外与已知边界：直接调用 `uploadDocument(kbId, file)`（不带 actor 的兼容重载）登记的任务没有账号维度，
worker 只做资源存在性检查；生产 HTTP 路径始终传入服务端认证得到的 actor。

## 后果

- 上传响应与 Embedding 解耦；状态可查询、失败可重试（重试是幂等的：同一文档最多一个未完成任务，
  由部分唯一索引 `exobrain_ingestion_job_active` 保证）。
- 代价：一次导入可能多次调用向量服务（有限次，最多 `max_attempts`），可能产生额外费用；
  状态可见性依赖 worker 进程运行（没有 worker 时任务一直 `QUEUED`）。
- 不提供：跨进程 exactly-once 的外部调用、任意执行位置的断点续跑、进度百分比、
  任务优先队列与超时取消 API。这些不在本任务范围。

## 备选方案与拒绝理由

| 方案 | 拒绝理由 |
| --- | --- |
| HTTP 线程内重试 Embedding | 阻塞请求、持有事务，用户仍需等待；崩溃后无法恢复 |
| Redis/Kafka/RabbitMQ 队列 | 引入新的基础设施，与“不新增中间件”的约束冲突；PostgreSQL 已能提供租约与 fencing |
| 复用 `exobrain_storage_cleanup` 表 | 删除清理与索引导入语义不同，会让删除任务被 worker 重试激活 |
| 只在任务开始时检查资源存在 | 构建期间删除/停用仍会发布到已删除资源；改为每次续租与发布前复核 |
| 用“上传成功即可检索”的语义 | 会把未发布的候选当成可用索引，违反“成功但零索引”禁止项 |
