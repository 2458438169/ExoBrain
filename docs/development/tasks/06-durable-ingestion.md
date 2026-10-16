# 06 — 持久化文档导入任务

**优先级：第二阶段。前置：03 的索引构建/发布边界稳定。可与 05 的独立后端文件并行。**

## 可直接分发的提示词

> 请执行 ExoBrain 开发任务 06。先阅读 docs/development/README.md、docs/development/contracts.md 和 docs/development/tasks/06-durable-ingestion.md。同时阅读 03 的交付，在保留文件预览、KB 权限及安全删除的前提下，把同步解析/Embedding 改为持久化导入任务。实现明确状态、有限重试、租约领取、重启恢复、删除后禁止发布，以及可见的导入进度。复用 03 的索引版本发布，不混用文件删除队列，不引入新的基础设施。交付真实 PostgreSQL/故障注入验证和 docs/development/deliveries/06.md。

## 代码入口与所有权

- [DocumentFacadeServiceImpl](../../../jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java)、DocumentController、DocumentMapper 与 DTO。
- 03 新增的索引构建/发布服务。
- [StorageCleanupService](../../../jchatmind/src/main/java/com/kama/jchatmind/service/StorageCleanupService.java)、[DocumentStorageServiceImpl](../../../jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentStorageServiceImpl.java)：需要保留的文件生命周期边界。
- [KnowledgeBaseView](../../../ui/src/components/views/KnowledgeBaseView.tsx)、[useDocuments](../../../ui/src/hooks/useDocuments.ts)、文档 API 类型。
- 负责新的 `ingestion/` 领域代码、带 `06` 编号的迁移及导入状态 UI。与 04/05 协调共享 API 类型，不修改对话运行调度。

## 最小可靠方案

采用现有 PostgreSQL 保存任务，先不增加 Redis/Kafka/RabbitMQ。状态按共享契约为 QUEUED、RUNNING、RETRY_WAIT、SUCCEEDED、FAILED、CANCELLED。

1. 请求校验 KB 写权限、文件格式/大小并保存受控文件；**文档记录与导入任务同一数据库事务提交**，返回 documentId 和 jobId/状态。未落库成功不得返回“已受理”。
2. 文件不参加数据库事务。使用受限暂存/原子文件发布、失败补偿和可恢复的孤儿清理规则；覆盖进程在保存文件与提交事务之间崩溃的窗口，不能只依赖进程内 afterCompletion。
3. Worker 用短事务领取已到期任务，可使用 `FOR UPDATE SKIP LOCKED`。任务包含尝试次数、nextAttemptAt、leaseOwner、leaseExpiresAt 和防旧 worker 写回的 fencing/version 字段。
4. 解析和外部 Embedding 在数据库事务之外运行。限制 worker 并发、文件大小、chunk 数、请求时间和尝试次数，采用有上限的退避；鉴权失败、格式错误、维度错误不无限重试。
5. 任务只携带服务器生成的资源 ID、文件/索引版本和必要 actor 信息；worker 开始与发布前重新检查账号状态、KB 归属、文档存在和模型绑定。不能从请求伪造的 userId 或原始路径取得授权。
6. 用户退出登录后的导入是否继续需要一个明确策略：建议已受理导入按资源所有权和账号有效性继续，账号停用/权限失效/删除时取消；与聊天运行按会话撤销的规则区分，并写 ADR 与测试。
7. 使用 03 的候选索引机制，只有当前有效租约持有者在短事务内确认源 hash、版本和资源状态后才能发布。租约过期的旧 worker 即使晚收到模型响应，也不能写回或覆盖新结果。
8. 重建失败保留旧可用索引；首次导入失败保留文件和失败状态供重试，不能出现“成功”但零索引、或部分 chunk 被检索。
9. 服务重启可重新领取超时任务；重复执行的向量调用可能产生额外费用，应说明这一限制。数据库唯一约束/版本键保证最终发布不产生重复 chunk，不能据此宣称外部调用 exactly-once。
10. 状态 API 和重试操作按 KB 所有权校验，普通用户不能重试公开只读文档。前后端一起发布，保留原 documentId，并明确异步受理与旧同步响应的行为变化。

## 删除与并发是必做项

- 删除文档/KB 时取消或失效其导入任务与候选索引，文件清理仍交给现有 StorageCleanupService。
- 清理队列、导入队列不得复用同一表；删除不能因 worker 重试重新创建 KB、文档、模型绑定或向量。
- Worker 发布与删除必须由数据库约束、版本检查/锁保证顺序，不能仅在任务开始检查一次 `exists`。
- 清理后仍持有已打开文件或正在计算的 worker 可能结束计算，但其结果必须被 fencing/资源检查拒绝。
- 批量重建与新上传同时发生时按 03 的索引快照规则处理；不把新文档放进未发布版本后永远查不到。

## 界面与 API

文档列表显示“等待处理 / 正在索引 / 可检索 / 处理失败”，失败时可查看安全原因并重试。若暴露进度，使用真实“已完成 chunk / 总 chunk”；不知道总量时显示阶段，不生成虚假的百分比。原文上传完成且有权限时可预览，即使索引暂未完成。

新增状态/重试路径先写入共享契约；游客只读公开文档状态，不开放上传或重试。账号写请求继续 CSRF。轮询应有退避、取消与可见页面限制，不为每个文档无限高频查询。

## 必做故障验证

| 注入点 | 必须满足 |
| --- | --- |
| 文件已写、DB 提交前崩溃 | 能识别并清理孤儿，不能误删其他文件 |
| DB 提交后、worker 启动前重启 | 任务可重新领取 |
| Embedding 暂时失败/永久错误 | 有限重试/明确终态，无半成品可检索 |
| 两个 worker 竞争同一任务 | 一个有效发布者；旧租约无法覆盖 |
| 构建中删除文档或 KB | 结果不发布，索引和文件最终清理 |
| 构建中改变账号状态或停用模型 | 后续工作按定义取消/失败，不越权 |
| 索引发布事务失败 | 旧索引保留；首次导入标失败可恢复 |
| 用户重复点击重试 | 不生成并行重复任务或重复 chunk |

使用真实 PostgreSQL、多 worker 和有界故障注入验证；不能只 mock repository。复用已有路径/符号链接保护测试，临时资源清理后给出证据。

## 完成标准

上传响应与长时间 Embedding 解耦，任务进度可查；重启能恢复，失败可处理，删除不复活。记录实际吞吐/响应时间测量条件而非预设收益。交付迁移/回滚、worker 配置、重试策略、磁盘孤儿处理与 07 可参考的领取测试，说明哪些仍不提供 exactly-once。
