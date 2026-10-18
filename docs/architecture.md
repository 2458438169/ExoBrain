# ExoBrain 架构：实际请求链路与恢复边界

本文描述**当前代码实际执行**的链路，并明确区分已实现与待实现能力。基线：提交 `39108e4`（任务 01–06 与 08 已合入）。

文档中的“待实现”目前**没有**属于任务 01–07 的条目：并发隔离的 Markdown 解析、可复现检索评测、正文索引与结构化检索（含索引版本与原子发布）、服务端校验的回答引用、真实执行追踪、持久化文档导入任务、持久化对话任务（提交与入队同事务、幂等键、租约领取与中断语义）均已落地。**仍然不存在**、也不在架构图上画成已有组件的能力：混合检索与重排、自动模型降级、PDF/Word 解析、任意执行位置的断点续跑与 exactly-once 语义。任务 07 的故障/重启场景尚缺自动化测试，未验证项逐条列在 [07 交付记录](development/deliveries/07.md)第 6 节。接口语义以 [共享契约](development/contracts.md) 为准，决策理由见 `docs/adr/`。

## 1. 部署形态

```text
浏览器（游客版 / 账号版两套独立构建）
   │  HTTPS
   ▼
Nginx（HTTPS 网关 / 反代，见 deploy/nginx.conf）
   ├── /                        → 游客前端（current/ui，base=/）
   ├── /admin/                  → 账号前端（current/admin，base=/admin/）
   ├── /api/demo/**, /sse/demo/**  → 匿名白名单（严格路径匹配 + 访客 Cookie）
   ├── /admin/api/ → 后端 /api/    ┐
   ├── /admin/sse/ → 后端 /sse/    ├ 需要应用会话
   └── 其他 /api/**, /sse/**       ┘
   ▼
Spring Boot 应用（loopback 18082，TZ Asia/Shanghai）
   ├── AuthFilter → Controller → ResourceAccess → Facade/Service → MyBatis
   ├── @Async 执行线程池（async-event-*，core 4 / max 10 / queue 100）
   └── SSE 订阅表（进程内）+ 15 秒注释心跳
   ▼
PostgreSQL 17 + pgvector（业务表 + exobrain_* 账号/模型/归属/清理表）
```

同一份 `ui/` 源码构建两次，`VITE_*` 是**编译期**配置，改容器环境变量不能把游客版变成账号版（见 [部署说明](../deploy/README.md#build)）。`AuthFilter` 只拦截 `/api` 与 `/sse/`，静态资源与登录页由网关直接提供。

## 2. 身份与访问控制（已实现）

| 主体 | 身份载体 | 校验位置 |
| --- | --- | --- |
| 游客 | HttpOnly Cookie `exobrain_demo`（Secure/SameSite=Lax，30 天，库中存哈希） | `DemoAccessPolicy`、`DemoController`、各 `Demo*` 服务 |
| 账号 | HttpOnly Cookie `__Host-exobrain_session`（服务端会话，库中存 token 哈希） | `AuthFilter` → `AuthContext` → `ResourceAccess` |
| 管理员 | 账号会话 + `ADMIN` 角色 | `AuthFilter`（`/api/admin/**`）、`ResourceAccess` 的归属判定 |

账号请求链路（`AuthFilter`，`OncePerRequestFilter`，优先级 `HIGHEST_PRECEDENCE + 20`）：

1. 只处理 `/api`、`/sse/`；`/api/demo`、`/api/demo/**`、`/sse/demo/**` 直接放行给游客策略。
2. 拒绝带 `;`、`\`、`%` 或 `/../`、`/./` 的原始路径，避免路径参数把公开路径变成受保护路径。
3. 校验 Cookie 会话（重复同名 Cookie 直接拒绝，不猜测哪个权威）；设置 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`。
4. 同源校验（`Sec-Fetch-Site: cross-site` 一律拒绝）；`/api/admin/**` 额外要求 `ADMIN`；非 `GET/HEAD/OPTIONS` 要求 `X-CSRF-Token`。
5. 身份放入 `AuthContext`（ThreadLocal），请求结束在 `finally` 中清理。

资源级授权集中在 `ResourceAccess`：UUID 规范化后按 `exobrain_resource_acl` 判定归属；普通用户对公开演示资源只读（写操作返回“共享资源为只读”）；管理员只对自己的资源、公开演示库以及**未归属的旧系统资源**有写权限，**不能**访问其他账号的私有知识库、文档、助手、会话（越权统一返回“不存在”）。控制器方法入口即调用 `requireSession` / `requireKb` / `requireDocument` / `requireMessage` / `validateChatMessage`，不依赖前端是否隐藏按钮。

## 3. 账号问答链路（已实现）

```text
POST /api/chat-messages
→ AuthFilter（会话 + CSRF + 同源）
→ ChatMessageController → ResourceAccess.validateChatMessage
    · 会话可读、会话 agentId == 请求 agentId
    · role 必须是 user，禁止客户端传 metadata
    · 助手配置与模型仍可用
→ ChatMessageFacadeServiceImpl.createChatMessage
    · 读取最近 100 条历史 → 插入 user 消息 → 序列化“历史快照”
    · 发布 ChatEvent(agentId, sessionId, content, actorId, authVersion, tokenHash, messageId, historySnapshot)
→ 立即返回 chatMessageId（HTTP 已结束）
```

后台线程（`ChatEventListener.handle`，`@Async`，`taskExecutor`）：

```text
ChatEvent
→ JChatMindFactory.createForSubmission
    · 用提交时的快照恢复会话记忆（不再读取执行期间新写入的消息）
    · 校验快照非空、属于本会话、最后一条就是触发消息
    · 校验运行时配置：ResourceAccess.validateRuntime(actorId, authVersion, tokenHash, sessionId, config)
    · 装配工具：每个 ToolCallback 包一层 ScopedToolCallback(delegate, allowedKbs, runtimeGuard)
→ JChatMind.run()：think → execute → 回填，直到 directAnswer / terminate / 自然结束 / MAX_STEPS=20
→ 每一步推送 SSE（AI_THINKING / AI_EXECUTING / AI_GENERATED_CONTENT / AI_DONE）
```

后台身份传播的要点（详见 [ADR 0001](adr/0001-background-authorization.md)）：执行线程**没有** `ThreadLocal` 权威，身份只用提交时快照；`validateRuntime` 会重新核对账号状态、`authVersion`、会话哈希、会话归属、助手配置与模型；`ScopedToolCallback` 在**每次**工具调用前重跑该守卫，并把 `KnowledgeTool` 的 `kbsId` 规范化后与助手获准 KB 白名单比对——模型给出的知识库 ID 不被信任。校验失败时静默停止，不向客户端泄漏内部信息；其他异常会先再次校验 actor，再发送 `AI_DONE` 失败状态。

边界：账号被停用、会话被撤销或角色变更后，下一次运行时校验（每次工具调用）即失败；已经发出的模型/工具调用不会回滚。

## 4. 游客问答链路（已实现）

```text
POST /api/demo/chat-messages
→ AuthFilter 放行（匿名白名单）
→ DemoController → DemoAccessPolicy（规范化配置的 agentId / publicKbIds / showcaseSessionIds）
→ DemoChatService（事务内持久化访客消息）
→ DemoExecutionService.submit
    · 进程内 running 集合做“同一会话一次一个执行”（重复提交 409）
    · 线程池执行 JChatMindFactory.create(...)（配置校验走 DemoAccessPolicy.validateAgent）
```

游客执行器**只注册四个工具**：`KnowledgeTool`（`DemoKnowledgeTool`）、`getDate`、`directAnswer`、`terminate`；`validateAgent` 只清空“本次加载的 DTO 副本”中的可选工具，数据库里的管理端选择不变。公开知识检索在工具执行时再次确认 `kbId` 在 `publicKbIds` 内。精选示例会话只读（发送、改名、删除、订阅 SSE 均拒绝）。

## 5. 文档导入与索引发布（已实现，仍同步）

```text
POST /api/knowledge-bases/{id}/documents （multipart）
→ DocumentController → ResourceAccess.requireKb(write)
→ DocumentFacadeServiceImpl.uploadDocument  @Transactional
    1. 插入 document 行（拿到 documentId）
    2. 保存原文件；注册事务同步：回滚时补偿删除上传文件
    3. 回写 document.metadata.filePath
    4. Markdown：解析 → 以任一级标题分段，每个章节生成一段 chunk
       · embedding 的输入是**标题**，保存的是该标题对应的正文
       · 每次解析新建 flexmark Parser，正文只在单次调用内存在（任务 01）
    5. chunk 行与 document 行在同一事务提交
```

即：索引的“发布”就是文档事务的提交——没有候选索引、没有 `indexVersion` / `indexRevision`、chunk `metadata` 为空、没有行号与来源哈希；失败时整体回滚并清理已写入的文件。旧库中已有的 chunk 不会被重新处理。

检索侧现状：`chunk_bge_m3(embedding vector(1024))` + `ivfflat (vector_l2_ops)` 索引（是否被规划器使用由数据库决定）；`ChunkBgeM3Mapper.similaritySearch` 限定 `kb_id`、按 `<->` L2 距离排序、`limit 3`、没有相关性阈值。`KnowledgeTools.knowledgeQuery(kbsId, query)` 返回拼接后的纯文本片段，没有命中状态、来源位置或距离。
检索侧现状：`chunk_bge_m3(embedding vector(1024))` + `ivfflat (vector_l2_ops)` 索引（是否被规划器使用由数据库决定）。`RetrievalService.retrieve` 在 SQL 里先限定知识库与当前活动索引版本，再按 `<->` L2 距离排序并 `limit topK`（`app.retrieval.top-k`，默认 3，可选 `max-distance` 拒绝阈值默认关闭）；`KnowledgeTool` 与演示工具都返回 `RetrievalEnvelope` JSON（状态、命中、来源位置、索引版本与距离），不再是拼接文本。
**已实现（03）**：正文切块与来源位置（`sourceHash` / `startLine` / `endLine`）、`indexVersion` / `indexRevision` 的候选构建与原子发布（ADR 0003）；**已实现（06）**：导入任务、短事务、有界重试、租约/fencing 与重启恢复（ADR 0006）。未重建的旧库继续是“标题向量 v1”行为，新上传遵循该库当前策略，候选与旧版本结果不混用（契约 §3）。

## 6. 回答、引用与执行记录（现状与差距）

已实现：`directAnswer` 交付答复；消息 `metadata` 保存 `toolCalls` / `toolResponse`；SSE 推 `AI_THINKING` / `AI_EXECUTING` / `AI_GENERATED_CONTENT` / `AI_DONE`（`AI_PLANNING` 仍是预留事件，未实现单独规划阶段）；历史可从数据库重新拉取。

**已实现（03/04/05）**：03 的结构化检索结果（`OK` / `NO_MATCH` / `UNAVAILABLE`）由 `KnowledgeTool` / 演示工具返回；04 的 `CitationRegistry` 只登记本次运行真实完成的 `KnowledgeTool` 结果，模型只能选择 `citationId`，`documentId`、行号与节选由服务端补全，界面点击走授权原文接口并按行定位；05 的 `runId` 与执行事件落库，聊天页可展开真实时间线。**仍未实现**：`AI_PLANNING` 独立规划阶段、自动切换备用模型（只有有限重试）。引用有效只证明来源可核对，不证明答案正确。

## 7. 删除与数据生命周期（已实现）

- 知识库删除（`KnowledgeBaseFacadeServiceImpl.deleteKnowledgeBase`，`@Transactional`）：删 `knowledge_base` 行 → 由外键级联删除 `document`、`chunk_bge_m3`、模型绑定与资源归属 → 解除助手引用 → **同一事务内**写入 `exobrain_storage_cleanup`（不在事务内抛异常，否则拒绝入队）。
- 文件清理（`StorageCleanupService`）：`afterCommit` 立即尝试；独立 `REQUIRES_NEW` 事务里 `FOR UPDATE SKIP LOCKED` 取行，先确认资源行确实已删除，再删文件，成功才删队列行；失败保留队列行并按分钟重试（重启后继续）。删除的文件立刻对所有 API 不可用，与物理删除是否成功无关。
- 文档删除同理（`enqueueDocument`）。删除知识库**不**删除聊天记录；`chat_session.agent_id` 在助手删除后置空但历史保留。
- 存储路径：使用规范化的知识库/文档 UUID 构造路径，拒绝越界路径，不跟随符号链接（Windows 上无法创建符号链接的用例按平台跳过并如实报告）。

## 8. 执行恢复边界（明确区分）

**已实现**

- 消息持久化：刷新/重连后可从 `chat_message` 恢复历史；SSE 断线重连 + 15 秒注释心跳（`SseServiceImpl`，emitter 30 分钟超时）。
- 会话生命周期：`AuthenticatedStreams` 每 15 秒确认账号会话仍存在，撤销/过期即关闭长连接。
- 失败可见：账号链路异常先复核 actor 再推 `AI_DONE` 失败状态；游客链路推同样状态。
- 文件清理的持久重试（见上一节）。

**没有实现（不得对外声称）**

- 对话/导入任务的持久化队列、领取与租约；重启后继续未完成的运行；崩溃后把运行标记为 `INTERRUPTED`；提交与入队原子化；重复提交的幂等（当前重复请求会再写入一条 user 消息）。
- 任意执行位置的断点续跑与 exactly-once 语义、运行级事件重放（`Last-Event-ID` 续传）。
- 自动模型降级/切换、混合检索、重排模型、知识图谱。

这些边界由 05（运行记录）、06（导入任务）与 07（对话任务）共同实现。**对话提交**现在把用户消息、固定输入快照与排队任务写在同一个事务里（`exobrain_chat_run_job`），由 worker 领取/续租/fencing 后执行，重启后仍能领取尚未开始的任务；已经执行而结果未知的运行按中断处理，不自动重放外部调用。**文档导入**同样是持久任务队列（`exobrain_ingestion_job`，租约 + fencing + 有界重试）。两条队列与文件删除清理队列互相独立。三者都**不提供** exactly-once：重复执行的模型或外部调用可能重复计费，运行记录只能如实反映已知尝试与未知结果。

## 9. 模型目录与密钥（已实现）

`exobrain_model` / `exobrain_model_state` / `exobrain_kb_embedding` 保存系统 LLM / Embedding 目录、启用状态与知识库绑定；API Key 用 AES-GCM 加密、密钥文件在仓库外且不得重新生成；`ChatClientRegistry` 按 `agent.model` 读取目录 ID，`ModelRuntimeService` 动态构建客户端。模型被停用或不存在时**明确拒绝运行**，不回退到静态客户端。每个知识库在创建时绑定 Embedding 模型，协议、模型名与 1024 维不可静默变更；更换供应商/模型/向量空间需要新建模型与知识库并重新导入。

## 10. 代码位置索引

| 关注点 | 入口 |
| --- | --- |
| 匿名/账号统一入口与网关白名单 | `deploy/nginx.conf`、`com.kama.jchatmind.auth.AuthFilter` |
| 资源级授权 | `com.kama.jchatmind.auth.ResourceAccess`、`ResourceAclRepository` |
| 游客公开策略与执行器 | `com.kama.jchatmind.demo.DemoAccessPolicy`、`DemoExecutionService`、`DemoKnowledgeTool` |
| 账号异步执行 | `com.kama.jchatmind.event.listener.ChatEventListener`、`config.AsyncConfig` |
| 运行时装配与工具白名单 | `com.kama.jchatmind.agent.JChatMindFactory`、`auth.ScopedToolCallback` |
| Agent 循环与状态机 | `com.kama.jchatmind.agent.JChatMind`、`AgentState` |
| 文档导入与索引写入 | `service.impl.DocumentFacadeServiceImpl`、`service.impl.MarkdownParserServiceImpl` |
| 检索 | `service.impl.RagServiceImpl`、`resources/mapper/ChunkBgeM3Mapper.xml` |
| 删除与文件清理 | `service.impl.KnowledgeBaseFacadeServiceImpl`、`service.StorageCleanupService` |
| 模型目录 | `com.kama.jchatmind.models.*`、`config.ChatClientRegistry` |

## 11. 检查与验证入口

后端确定性单元、真实 PostgreSQL 集成、前端 lint 与双构建使用同一份逻辑：`scripts/check.sh`（Linux / CI）与 `scripts/check.ps1`（Windows），参数与步骤见脚本头部说明。真实语义评测（外部 Embedding/LLM）不属于常规检查，需显式运行并在无凭据时如实标记“未执行”。
