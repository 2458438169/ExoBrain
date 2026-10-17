# ADR 0005 — 真实执行追踪

- 状态：已实现（任务 05）
- 日期：2026-10-17
- 相关：[共享契约](../development/contracts.md) 第 5 节，[任务 05](../development/tasks/05-run-tracing.md)，ADR 0004（可核验引用）

## 问题

对话页面已有工具调用与结果，但用户和运维都无法回答：这次回答用了哪个助手、哪个模型、执行了多久、哪些工具真的执行了、模型是否重试过、失败原因是什么。难点不是画时间线，而是**时间线必须是真实发生的**：模型“计划”调用但没执行的工具、被上游繁忙吞掉的尝试、服务商没有返回的用量，都不能被补齐或估算；重启后遗留的运行也不能一直显示“执行中”。

同时，05 不负责调度：没有领取、租约与重放之前，不能把“有运行记录”说成“任务可靠入队或可断点续跑”。

## 考虑过的方案

1. **前端计时 + 现有 SSE 状态推算**：实现最快，但刷新、断线、移动端都会得到不同的数字，且无法证明工具真的执行。否决。
2. **把执行轨迹写进消息 metadata**：改动小，但事件的顺序、耗时、重试与终态都要求独立、可查询、可按会话列表读取；塞进消息 JSON 会让历史消息和观测数据互相拖累。否决。
3. **完整分布式 tracing（OpenTelemetry 等）**：字段丰富，但需要额外组件与导出链路，超出本次范围，且隐私边界更难守。否决。
4. **服务端运行摘要 + 顺序事件（独立表、受权读取 API）**：运行 ID 与服务端生成，事件在真实边界结束时写一行，读取按会话所有权重新校验。**采纳**。

## 最终选择

- 新包 `com.kama.jchatmind.execution`：`RunSummary`（契约 §5 字段）、`RunEventRecord`、`RunStatus`（含允许的迁移）、`TokenUsage`、`RunRepository` / `JdbcRunRepository`、`RunRecorder`（计时与事件写入）、`RunLifecycleService`（创建与终态）、`RunQueryService` / `DemoRunService`（受权读取）、`TimedToolCallback` + `ToolTimingCollector`（逐工具计时）。
- 迁移 `deploy/migrations/20261017-05-runs.sql` 新增 `exobrain_run` 与 `exobrain_run_event`；只增量创建，不改既有迁移，不写 GRANT。
- 埋点位置（不重写模型循环）：
  - 模型调用：`callModelWithRetry` 里对 `requestModel` 的每次尝试计时，第一次为 `MODEL_CALL`、重试为 `MODEL_RETRY`，都带 `retryAttempt`；上游返回“繁忙”提示的尝试记 `FAILED` + `UPSTREAM_BUSY`。
  - 工具：`JChatMind` 构造时用 `TimedToolCallback` 包装真实回调，每次 `call` 记录真实起止；`ToolCallingManager` 返回后按“序号 + 工具名”与本次模型请求的 `toolCallId` 对齐，对不上就不写 ID。计划但未执行的调用不产生事件。
  - 检索：`KnowledgeTool` 响应能解析成契约信封时，额外记录 `RETRIEVAL`，按 `retrievalId` 关联。
  - 落库：每次 `createChatMessage` 记 `PERSIST_MESSAGE`（成功/失败）。
  - 运行终态：账号在 `ChatEventListener`、游客在 `DemoExecutionService` 写入；`JChatMind.state() == ERROR`（连续两轮没有答复）记 `FAILED/NO_ANSWER_PRODUCED`，异常按安全错误码分类，授权撤销记 `AUTHORIZATION_REVOKED`。
- 时间：事件耗时用 `System.nanoTime()`（同一进程单调时钟）；`startedAt` 是墙钟 UTC。运行耗时用记录器自己的单调起点，和事件同源。
- 状态机：`QUEUED → RUNNING → {SUCCEEDED, FAILED, CANCELLED, INTERRUPTED}`；终态不可改写，重复写入同一终态幂等。数据库用条件更新 `WHERE status IN ('QUEUED','RUNNING') OR status = ?` 兜底，迟到的 RUNNING 不会覆盖终态。
- 重启遗留的 RUNNING：没有租约就不猜。读取时若 `status = RUNNING` 且心跳超过 `app.execution.stale-run-seconds`（默认 300 秒），API 返回 `INTERRUPTED` 且 `interrupted = true`，数据库里的原始状态保持不变；真正的恢复由任务 07 负责。
- 失败策略：主记录创建失败 → 不执行并发送明确的失败提示（不显示成功启动）；中途事件写入失败 → 保留已有事件、置 `trace_incomplete`、记一条安全 WARN，不补造缺失事件。
- 用量：只累加服务商返回的数字；Spring AI 在服务商未返回时给出全 0，`TokenUsage.of` 把“全 0/全空”视为未提供（null），`total` 只在服务商提供或 input+output 都已知时给出。
- 读取：账号 `GET /api/chat-sessions/{sessionId}/runs`、`GET /api/runs/{runId}`，每条路径重新走 `ResourceAccess.requireSession`；游客 `/api/demo/...` 对应路径只允许访客自己的会话或已发布精选，并用白名单投影（无 sessionId、agentId、配置哈希、模型目录 ID、toolCallId、retrievalId、eventId）。
- 消息关联：服务端把 `runId` 写进消息 metadata（可选字段），最终答复消息 ID 回写到 `exobrain_run.answer_message_id`。旧消息没有该字段就不显示执行详情。
- SSE：`AI_*` 消息的 metadata 增加可选的 `runId`；断线后仍以数据库历史为准，不宣称支持任意 `Last-Event-ID` 续传。

## 代价

- 每个真实动作多一次 INSERT（模型调用、工具、检索、每条消息落库）以及一次心跳 UPDATE；写入失败只降级为“详情不完整”。没有做过吞吐/延迟压测，本轮不提供性能数字。
- 事件与运行摘要随会话级联删除（`chat_session` 删除即删除运行），没有独立的清理任务；长期保留策略留给运维，见交付记录。
- 运行记录只覆盖 05 开始之后的执行；旧对话显示“此记录未保存执行详情”，不用当前配置或浏览器计时补齐。
- 助手配置只存 SHA-256 与开始时的名称/模型名，不存配置快照；需要“当时提示词”时必须另建受限快照表（07/后续任务）。

## 验证

- 后端单元：`RunTracingExecutionTest`（正常、问候无工具、工具失败、两次重试后成功、步数上限、授权撤销、无答复、用量累加）、`RunModelTest`（状态机、用量、事件写入失败）、`DemoExecutionServiceTest` / `AuthListenerFailureTest` 回归。
- 真实 PostgreSQL：`RunTracingIntegrationTest`（迁移重复执行、摘要与事件 round-trip、顺序唯一、状态迁移与负耗时约束、重启遗留 RUNNING、账号与游客访问矩阵、公开精选白名单）。
- 前端：`npm run lint` 与游客版/账号版两套构建；浏览器交互见交付记录（未执行则如实标注）。

## 何时重新评估

- 引入领取、租约或队列（07）时，`QUEUED`/心跳/终态语义要一起修订，不能把 05 的“只观测”说成“可恢复”。
- 需要“历史配置快照”或按运行重放时，必须新增受限服务端记录，不能把配置或正文放进公开 trace。
- 事件量增长到需要采样、归档或独立保留策略时，先量化写入开销再决定，不允许为了性能删掉“失败/重试”证据。