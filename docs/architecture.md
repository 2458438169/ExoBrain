# ExoBrain 架构：实际请求链路与恢复边界

本文对应 UX-01–06 独立验收候选代码（2026-10-19）。实际部署版本与检查回执见 [UX-06](development/visitor-experience/deliveries/UX-06.md)；内容和耗时测量见 [UX-05](development/visitor-experience/deliveries/UX-05.md)。历史交付记录保留当时的状态，不能把其“未实现”段落当作当前结论。

## 1. 部署形态

```text
浏览器：游客版 / 账号版，同一 React 源码独立构建
   │ HTTPS
Nginx：严格路径与方法白名单、静态资源、API / SSE 反代
   ├── /                         → current/ui
   ├── /admin/                   → current/admin
   ├── /api/demo/** /sse/demo/** → 游客 Cookie + 公开资源策略
   └── /admin/api/** /admin/sse/** → 应用会话 + 资源归属
   │
Spring Boot：控制器 / 授权 / 提交服务 / 数据库 worker
   ├── RunWorker：持久对话任务、每次运行独立租约心跳
   ├── IngestionWorker：持久导入任务、候选索引构建与发布
   ├── StorageCleanupService：持久文件清理
   └── SSE：进程内订阅、状态通知；历史和运行摘要可重新查询
   │
PostgreSQL 17 + pgvector：业务数据、权限、任务、运行事件与版本化索引
```

`VITE_*` 是编译期配置。游客和账号资源必须分别构建；修改容器环境不能改变已经构建的前端。公开取消路径只允许 POST，其他方法由网关返回 405；未发布路径仍拒绝访问。模板与实际生效网关必须分别核对。

## 2. 身份与访问控制

| 主体 | 身份与权限 |
| --- | --- |
| 游客 | HttpOnly Cookie；只能访问已发布资料、自己的会话和明确发布的只读示例 |
| 普通账号 | 服务端会话 + CSRF / 同源校验；私有知识库、文档、助手和会话按账号归属 |
| 管理员 | 管理账号与系统模型；不获得其他账号私有资源的读取、检索或修改权 |

`AuthFilter` 校验账号会话和写请求。`ResourceAccess` 及 `ResourceAclRepository` 执行资源授权；`DemoAccessPolicy` 执行公开范围与精选只读规则。后台任务保存提交身份快照，在执行与工具调用前重新授权，不依赖请求线程的 `ThreadLocal`。

公开运行投影只有会话所有者能看到 `triggeringMessageId` / `answerMessageId`。精选示例中的这两个字段恒为 null。原文与导入状态使用同一公开 KB 策略；游客没有导入重试权限。

## 3. 账号与游客提交

```text
POST /api/chat-messages 或 /api/demo/chat-messages
→ 身份、会话、助手、内容与公开范围校验
→ RunSubmissionService.submitAccount / submitDemo
→ 一个事务内写用户消息、固定输入快照、exobrain_run 和 exobrain_chat_run_job
→ 返回 chatMessageId、runId、runStatus、idempotentReplay
```

幂等范围包含主体、会话与键。同一范围的同键同内容返回原运行；同键异内容冲突。没有同键的两个请求属于两个意图，不能宣称全局去重。数据库约束保证同一会话最多一个活动任务。前端同一发送意图在响应丢失、手动重试和重连时复用键，受理成功后不自动重新提交。

## 4. 运行、心跳与结果提交

`RunWorker` 通过短事务领取任务，`RunExecutor` 重新授权并运行 Think-Execute 循环。模型和检索外部调用不占数据库事务；工具调用前执行运行权限与租约检查。

- 每次运行有独立心跳调度器，续租使用独立连接、短事务和 `lock_timeout`；另一运行的行锁不会堵住唯一心跳线程。
- 检查点遇到锁阻塞时有界重试；必要时无锁读取任务确认 status、fencing、租约期限和取消标记。摘要心跳也有界，写后再次确认；最终确认后直接放行，不再做可能阻塞的数据库写。
- `RunResultWriter` 在同一短事务锁任务、校验运行态与 fencing、写消息并关联最终答案。取消、终态或接管后的旧 worker 不能补写迟到答案。
- `RunTerminalWriter` 仲裁终态。取消请求跨过心跳/租约期限仍按 CANCELLED 处理；不能误标成重启中断。

已发出的外部调用不能撤销，也可能已经计费。检查点和结果提交守卫控制的是后续执行与落库，不提供 exactly-once。任务行上真正未提交的取消请求还没有成为其他事务可见的事实。

游客运行时只注册 `KnowledgeTool`、`getDate`、`directAnswer`、`terminate` 四工具，检索再次校验允许的知识库。公开助手中的遗留可选数据库工具字段需通过正常配置 API 清理，不开放数据库查询能力。

## 5. 文档导入与索引发布

```text
POST /api/documents/upload（multipart）
→ 权限与文件校验，保存 Markdown 原文件
→ 短事务登记 document 与 exobrain_ingestion_job，返回 documentId / jobId
→ IngestionWorker 领取，解析正文、调用绑定的 Embedding、构建候选 revision
→ IngestionPublisher 校验租约/fencing 与旧活动 revision，原子切换活动索引
```

新库使用 `body-v1`：按章节与正文长度切块，向量输入包含章节路径与正文；保存 `documentId`、`sourceHash`、行号范围和索引 revision。未重建的旧库保留标题向量策略，不混用候选与活动版本。候选失败保留旧活动索引；同库并发导入可能发生 `INDEX_CONFLICT`，发布资料逐份串行导入。

`RetrievalService` 在 SQL 中限定获准知识库及其活动 revision，用 pgvector L2 距离取 top K，返回 `RetrievalEnvelope`（OK / NO_MATCH / UNAVAILABLE、片段、来源与距离）。向量维度为 1024；阈值默认关闭。数据库规划器决定是否使用 ivfflat 索引，不能仅凭存在索引就声称查询一定走索引。

## 6. 回答、引用与界面恢复

`CitationRegistry` 只登记本轮真实完成的知识检索；模型选择 citationId，来源字段由服务端补全。原文预览校验规范化正文 SHA-256，只有 hash 与行范围均有效才高亮；旧记录缺位置或原文已变更时给出可读降级。引用有效不等于结论正确。

运行摘要和真实事件记录排队、模型、重试、工具、检索与落库时间。前端以服务端消息关联和 `metadata.runId` 归组，不按相邻位置猜运行；空工具消息不作为答案开头。来源与过程默认紧凑，长回答可展开；上滚、选择文字或阅读来源时保持阅读位置。

SSE 提供阶段与消息通知，不是逐字正文流式。`AI_DONE` 触发状态核对，数据库终态才结束等待；旧 runId 的事件不改变新运行。断线、重连、页面恢复可见及活动轮询都会核对运行/历史，断线本身不代表任务结束。接口失败与“确实没有记录”分开，已读到的答案不会因后续读失败被清空。

## 7. 删除与生命周期

删除知识库或文档时在数据库事务内解除相关引用并登记 `exobrain_storage_cleanup`。提交后清理原文件，临时失败保留队列重试；资源一旦删除，其 API 立即不可读，不等文件物理删除。删除知识库不删除既有会话消息。存储路径按规范 UUID 限定，拒绝路径越界和符号链接。

公开 v2 切换时，助手的 `allowedKbs` 只指向新库；`public-kb-ids` 保留旧库与新库，使旧会话引用继续可读。精选必须由真实运行审核后发布，不能上传合成测试答案充当实际能力。

## 8. 恢复边界与验证范围

| 状态 | 恢复行为 |
| --- | --- |
| QUEUED | 重启后仍可领取执行 |
| RUNNING 且租约有效 | 心跳续租；前端可刷新恢复状态 |
| 已运行但租约过期、结果未知 | 标记 INTERRUPTED，不自动重放外部调用 |
| cancel_requested 后过期 | 标记 CANCELLED / CANCELLED_BY_USER |
| 导入任务失败 | 按固定错误分类有界重试，旧活动索引不受失败候选影响 |

真实 PostgreSQL、并发 HTTP、浏览器丢响应、SSE TCP 断开、行锁、取消、旧事件与阅读恢复已在隔离环境验证，详见 UX-06。多主机部署、网络分区、真手机软键盘与多模型效果/成本对照仍缺验证。没有实现逐字正文流式、`Last-Event-ID` 事件续传、自动模型降级、混合检索/重排、PDF/Word 解析、任意执行位置的断点续跑和 exactly-once。

## 9. 模型目录与密钥

LLM / Embedding 由 `exobrain_model` 目录管理，API Key 使用 AES-GCM 与仓库外持久密钥加密。运行按已启用模型构建客户端；模型不存在或被停用时明确拒绝，不静默回退。知识库固定 Embedding 绑定与维度；更换向量空间需新建绑定和库并重新导入。

## 10. 代码与检查入口

| 关注点 | 目录或入口 |
| --- | --- |
| 授权、公开访问 | `auth/**`、`demo/**`、`deploy/nginx.conf` |
| 运行提交、租约、结果守卫 | `runs/**` |
| 摘要、事件与读取 | `execution/**` |
| 导入与活动 revision | `ingestion/**`、`retrieval/**` |
| 发送与恢复 | `ui/src/components/views/AgentChatView.tsx` |
| 阅读关联、响应校验 | `agentChatView/chatTurns.ts`、`ui/src/api/runResponses.ts` |
| 原文定位 | `ui/src/components/modals/DocumentPreviewDrawer.tsx` |
| 本地与 CI | `scripts/check.ps1`、`scripts/check.sh`、`.github/workflows/ci.yml` |

统一检查包含后端单元、真实 PostgreSQL、前端 lint、状态回归和双构建。真实模型/Embedding 的内容测量另行执行，缺凭据或未执行的项目如实标记，不算通过。
