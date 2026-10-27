# 优化任务共享契约

与[开发指南](README.md)配套。**本文件定义待实现的 v1 协议；基线代码尚未提供下列新增字段或接口。** 字段调整应由生产者和消费者一起修订本文。示例类型用于约定语义，不是已经生成的 SDK。

## 1. 现有边界保持稳定

| 能力 | 基线入口/行为 | 新增时的约束 |
| --- | --- | --- |
| 知识检索工具 | `KnowledgeTool(kbsId, query)`，返回字符串 | 保留工具名与参数名；字符串内部可改为版本化 JSON |
| 登录 API | 后端 `/api/*`；线上账号前端走 `/admin/api/*` | 使用现有 HTTP 客户端、会话和 CSRF，不硬编码绕过代理的 URL |
| 游客 API | `/api/demo/*` | 继续按发布名单与访客 Cookie 限制；新路径需要更新 Nginx 的严格白名单 |
| 文档原文 | `GET .../documents/{documentId}/content` | 继续返回 `ApiResponse.data = {documentId, filename, content}`；扩展字段应可选 |
| SSE | `AI_GENERATED_CONTENT / AI_PLANNING / AI_THINKING / AI_EXECUTING / AI_DONE` | 新 metadata 可选；旧消费者可忽略；历史拉取仍能恢复界面 |
| 消息 metadata | `toolCalls / toolResponse` | 保留旧字段与旧记录读取；新增引用/运行 ID 不从客户端输入中信任继承 |

账号校验入口是 `ResourceAccess`，运行时工具仍通过 `ScopedToolCallback` 检查获准 KB 与当前执行身份；游客使用 `DemoAccessPolicy`。新增服务不能因为绕开 Controller 就丢掉检查。

## 2. 检索结果：03 生产，02 / 04 / 05 消费

~~~typescript
type RetrievalStatus = "OK" | "NO_MATCH" | "UNAVAILABLE";

interface SourceLocation {
  documentId: string;       // 数据库 UUID，不是文件路径
  documentName: string;     // 经权限校验后的显示名
  headingPath: string[];
  sourceHash: string | null;
  startLine: number | null; // 从 1 开始，包含端点
  endLine: number | null;
}

interface RetrievalHit {
  citationId: string;       // 本次 retrievalId + ":" + rank，服务端生成
  chunkId: string;
  kbId: string;
  rank: number;            // 从 1 开始
  content: string;         // 实际交给模型的资料片段，不是预期答案
  source: SourceLocation;
  indexVersion: string;    // 算法/配置版本，例如 legacy-title-v1、body-v1
  indexRevision: string;   // 一次已发布索引的版本标识
  metric: "l2";
  distance: number;        // 有限非负值，越小越近；不是概率
}

interface RetrievalEnvelope {
  schemaVersion: 1;
  retrievalId: string;
  status: RetrievalStatus;
  hits: RetrievalHit[];
  errorCode?: string;      // 安全的固定错误码，不含服务商原始异常
}
~~~

规则：

- `OK` 至少有一个 hit；`NO_MATCH` 表示成功检索但无足够相关资料；`UNAVAILABLE` 表示服务、模型或索引不可用。后二者的 hits 必须为空，不能拿异常当成“资料不足”来提高评测分数。
- 未授权属于访问失败，不伪装成一次成功检索。授权在调用模型/向量查询之前执行；无权 KB 的名称、片段、数量或距离不得进入响应、缓存和公开日志。
- 查询由服务端限定 KB、当前索引版本和模型绑定。若增加缓存，缓存键包含访问范围、KB、模型与索引版本；权限变化/删除后不可继续返回旧缓存。
- 先保留每次工具调用只检索一个获准 KB。跨多个 KB 的调用可由运行时组合，但不能直接比较不同 Embedding 模型空间的 L2 距离。
- topK 服务端配置，初始仍为 3，范围建议 1–10；阈值从开发集校准。未校准时不使用随意指定的“80% 相似度”或把 L2 做 `1 - distance` 伪装成置信度。
- `retrievalId` 表示一次实际检索。复用缓存也生成本次关联标识，并在内部记录是否命中缓存；不能借用另一用户的检索记录。
- 模型上下文限制通过分块与检索预算处理。不得返回片段全文的行号，却在正文中间无标识地删掉关键句。截断需保留与实际证据对应的来源范围。
- v1 对外保留 `metric: "l2"`。若评测支持更换距离策略，需同步协议、数据库算子和索引，并重新构建比较；不得悄悄把同一字段换成另一种含义。

**存储注意：** 基线 `chunk_bge_m3.metadata` 是 TEXT，不是 JSONB；`ChunkBgeM3DTO.MetaData` 当前为空。03 可以存受控的 JSON 字符串，或增量迁移成 JSONB，但必须同步 mapper 类型/CAST，并用真实 PostgreSQL 验证。只测 mock 不能验证此处兼容性。

## 3. 来源位置与索引版本：03 / 04 / 06 共用

- 来源哈希针对原文件经 UTF-8 解码、移除首个可选 BOM、将 CRLF/CR 统一为 LF 后的文本，再以 UTF-8 编码计算 SHA-256。**只用于比对，不改变原文接口展示的原始换行。**
- 行号基于上述标准化文本；两端包含，满足 `1 <= startLine <= endLine <= 总行数`。使用 AST 对原文的位置或等价的确定性映射，不能从清洗后的文字长度反推原文偏移。
- 03 和 04 必须共用 BOM、CRLF、重复标题、中文、代码块、表格的黄金夹具。同名章节依靠源位置区分，不只依靠标题 slug。
- 旧 chunk 没有可靠位置时 `sourceHash/startLine/endLine` 可为 null，界面只能打开整份文档。不能按片段在原文中的第一次字符串匹配假定位置准确。
- `indexVersion` 表示切块/Embedding 输入/距离配置的语义版本，`indexRevision` 表示一次发布的具体索引。文件改变和重新分块不能让旧引用静默指向另一段正文。
- 03 实现“构建候选索引 → 核对文档清单、源哈希与模型 → 原子发布可用版本”的边界，06 复用它。批量重建的候选生成不能在长期数据库事务内等待外部 Embedding；同步上传的任务化与短事务改造由 06 完成。
- 未重建旧 KB 可继续使用旧策略；新上传必须遵循该 KB 当前的策略。切换一个 KB 时不得混用候选与旧版本的结果，失败保持旧索引可用。具体表结构由 03 写 ADR，但对消费者的上述字段含义固定。
- KB 删除、文件删除、模型停用或权限变化会使候选发布失效。发布前必须再次检查，不能因为任务提交时有权限就写回已经删除的资源。

## 4. 回答引用：04 生产，05 保留

~~~typescript
interface AnswerCitation {
  label: number;           // 展示为 [1]、[2]，由服务端按有效引用排序
  citationId: string;
  retrievalId: string;
  kbId: string;
  chunkId: string;
  source: SourceLocation;
  indexVersion: string;
  indexRevision: string;
  excerpt: string;         // 来自本轮实际检索片段的受控节选
}

// 在现有 metadata 中增量增加：
interface NewMessageMetadataFields {
  citations?: AnswerCitation[];
  runId?: string;          // 05 引入；04 不依赖它才可正常工作
}
~~~

- 04 在一次运行的本地上下文中收集真实工具结果，按 toolCallId 与当前调用匹配；只接收受信任的 KnowledgeTool 执行结果。用户消息、旧历史、模型文本和客户端 metadata 不能登记来源。
- 可兼容扩展 `directAnswer` 的可选 `citationIds` 参数；只有 `answer` 的旧调用仍可完成普通答复。模型可以选择来源 ID，但不能决定 documentId、行号、片段内容或任意跳转 URL。
- 服务端校验 ID 属于本次运行已完成的检索、来源仍在获准范围，生成结构化 citations。未知、跨会话、跨用户、未执行工具的 ID 不生成可点击引用；记录安全的校验失败信息。
- 正式答复中的 `[n]` 表示 `citationIds` 第 n 项（从 1 开始），**不是**检索 rank、检索结果列表序号或文档行号；最终展示编号由服务端校验、去重、重排后确定，来源经排序、去重后同步把正文编号映射到最终 label。实际引用无效来源或越界位置时，在正文落库前以 `INVALID_ANSWER_FORMAT` 失败。普通无引用答复与 Markdown 代码、链接、转义示例不改写。
- 普通文字中出现 `[1]` 不证明有引用。只把结构化 citations 中对应的标签渲染成来源操作；“本次检索到的资料”与“回答实际引用的资料”应区分。
- 引用有效只证明来源身份和位置可核验，不自动证明该来源支持整句话。支持程度由 02 的事实/引用评测检查，不能标注“答案已验证正确”。
- 点击时再通过已有授权原文接口读取，校验 hash 后定位。来源变更显示“文档已更新，原引用位置不可核验”；删除/无权访问显示“来源不可用”，不回退到裸文件路径。
- 历史会话按原有权限保留已有消息；删除知识库不连带删除聊天。不通过历史引用重新获得当前无权读取的文件。公开精选的序列化必须检查新增字段及来源依赖；来源撤下时过滤或撤下相关精选，不能只隐藏链接而继续公开受限片段。
- 04 负责 `DemoCatalogService` 的新字段过滤与精选回归。不得把全量内部检索对象直接透传到公开页面。

## 5. 执行记录：05 生产，07 扩展调度

拟新增 `RunSummary`：

| 字段 | 语义 |
| --- | --- |
| `schemaVersion / runId / sessionId / triggeringMessageId` | 版本与服务端关联标识 |
| `agentId / agentNameAtStart / agentConfigHash` | 本次运行实际选择的助手及配置标识 |
| `modelCatalogId / modelNameAtStart` | 实际模型；无密钥、服务内网地址 |
| `status` | `QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / INTERRUPTED` |
| `submittedAt / startedAt / finishedAt` | UTC ISO-8601；未发生的时间为 null |
| `durationMs` | 有定义的实际执行耗时；排队耗时单独记录 |
| `inputTokens / outputTokens / totalTokens` | 服务商提供的用量；缺失为 null |
| `errorCode` | 安全错误分类；不给公开用户原始堆栈 |

每条事件最少有 `eventId, runId, sequence, type, startedAt, durationMs, status`；按需带 `toolCallId, retrievalId, retryAttempt`。只记录实际模型调用、工具调用、检索、持久化与错误事件，不把模型私有推理文本当执行轨迹。

建议账号读取接口为 `GET /api/chat-sessions/{sessionId}/runs` 和 `GET /api/runs/{runId}`，游客对应 `/api/demo/...`；这是 05 需实现和验证的提案。必须按会话所有权授权，不能仅凭不可猜 UUID 放行。新增游客路径需由 08 同步网关白名单。

- 时间长度在同一进程内用单调时钟测量；不能用刷新时间或 UI 动画推算。未知 token 不填 0；有用量但无完整价目表时不显示精确费用。
- 05 可以实现运行记录落库，但不因此声称任务已可靠入队或能够断点续跑；07 才负责领取、租约与恢复。
- SSE 可附带 `runId/eventId/sequence`，断线后按数据库记录恢复并去重。先约定历史拉取恢复，未实现完整事件重放前不宣称支持任意 Last-Event-ID 续传。
- 重复事件不能改变已确定的终态。执行期间停用账号、撤销会话、删库或停用模型，必须按现有运行时授权策略停止后续调用。
- 原始 actor、授权版本、必要的服务端会话校验信息只存受限服务端记录；不要在工具参数、公开 DTO 或日志里输出 Cookie、会话令牌/哈希、API Key、完整配置快照和私有正文。

## 6. 导入任务与执行任务分开

06 的文档导入状态：`QUEUED → RUNNING → SUCCEEDED`；临时失败进入 `RETRY_WAIT` 后重试，超过上限为 `FAILED`；资源删除/权限撤销为 `CANCELLED`。任务成功意味着可用索引已原子发布，文件接收成功不等于可检索。

拟给 `CreateDocumentResponse` 增量加入可选 `jobId/indexStatus`，文档列表也返回状态；状态/重试路径由 06 在实现前定稿。既有 `documentId` 保留。切换为异步上传时，前后端同批发布并明确旧客户端兼容策略。

**06 已定稿的导入状态/重试路径（实现于任务 06）：**

| 路径 | 授权 | 说明 |
| --- | --- | --- |
| `POST /api/documents/upload` | KB 写权限 + CSRF | 返回 `documentId` + `jobId` + `indexStatus=QUEUED`；文件已保存、任务已排队，索引尚未发布 |
| `GET /api/documents/{documentId}/ingestion` | KB 读权限 | 该文档最新任务的状态；游客可读公开文档状态。响应只含 `jobId/status/attempts/chunksDone/chunksTotal/errorCode/errorMessage/retryable/nextAttemptAt/indexRevisionId`，不含路径、actor、租约或服务商异常 |
| `POST /api/documents/{documentId}/ingestion/retry` | KB 写权限 + CSRF | 幂等：同一文档最多一个未完成任务时直接返回它；普通用户不能重试公开只读文档 |

`GET /api/documents/kb/{kbId}` 的每个文档增量返回 `jobId/indexStatus/indexErrorCode/indexErrorMessage/chunksDone/chunksTotal`
（未带任务的历史文档这些字段为空）。`status` 取值固定为
`QUEUED/RUNNING/RETRY_WAIT/SUCCEEDED/FAILED/CANCELLED`；`chunksTotal` 未知时为 `null`，此时界面只显示阶段，
不生成百分比。公开演示视图（`/api/demo/documents/kb/{id}`）同样返回这些只读字段，不提供上传或重试。


07 使用第 5 节运行状态，承诺先做到：

- 消息与任务同一事务持久化，返回成功后排队任务在重启后可继续领取。
- 同一会话一个活动任务，幂等请求不会产生第二条用户消息或第二个运行。
- 崩溃时已经运行的任务先标记 `INTERRUPTED`，不自动重放未知是否成功的有副作用工具。尚未运行的任务可恢复。
- 运行记录的持久化、排队恢复和任意执行位置的无重复续跑是不同能力；不得把前两者描述成 exactly-once 执行。

两类任务可复用经验证的领取/租约代码模式，但不与现有文件删除清理队列表混用。删除清理负责移除文件，导入负责发布索引，对话负责模型与工具执行，各自保留状态与失败语义。
