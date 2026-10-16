# 07 — 持久化对话任务与中断恢复

**优先级：第二阶段，最后一项运行时改造。前置：05；参考 06 的租约经验。**

## 可直接分发的提示词

> 请执行 ExoBrain 开发任务 07。先阅读 docs/development/README.md、docs/development/contracts.md 和 docs/development/tasks/07-durable-runs.md。同时阅读 05 的交付，把账号与游客的进程内任务提交改为 PostgreSQL 持久队列，保证消息与任务原子提交、同会话串行、幂等请求和重启后的排队恢复。已经执行但结果未知的有副作用工具不得自动重放；先诚实标记中断。保持运行时重新授权、公开四工具白名单与 SSE 历史恢复。交付故障测试、恢复边界和 docs/development/deliveries/07.md，不宣称实现任意步骤无重复续跑。

## 代码入口与范围

- [ChatMessageFacadeServiceImpl](../../../jchatmind/src/main/java/com/kama/jchatmind/service/impl/ChatMessageFacadeServiceImpl.java)：用户消息、历史快照、事件发布。
- [ChatEvent](../../../jchatmind/src/main/java/com/kama/jchatmind/event/ChatEvent.java)、[ChatEventListener](../../../jchatmind/src/main/java/com/kama/jchatmind/event/listener/ChatEventListener.java)、[AsyncConfig](../../../jchatmind/src/main/java/com/kama/jchatmind/config/AsyncConfig.java)。
- [DemoChatService](../../../jchatmind/src/main/java/com/kama/jchatmind/demo/DemoChatService.java)、[DemoExecutionService](../../../jchatmind/src/main/java/com/kama/jchatmind/demo/DemoExecutionService.java)。
- JChatMind / Factory 与 05 的执行记录服务，账号/游客提交响应和前端运行状态。
- 负责带 `07` 编号的增量迁移、持久调度及相关 API。不是把两个执行器合并成一个失去权限边界的通用管理员执行器。

## MVP 承诺

- 返回“已受理”表示用户消息、必要快照与运行任务已原子落库；重启不丢失尚未开始的任务。
- 同一个会话最多一个活动运行；重复网络提交通过幂等键返回原 message/run，不再向模型发送第二遍。
- 排队运行重启后重新校验身份、配置和资源再执行；已经开始而执行结果不确定的运行进入 INTERRUPTED。
- 当前阶段不做任意模型/工具步骤的透明续跑，也不自动重放发送邮件等副作用操作。

## 实施步骤

1. 在同一事务中创建用户消息、固定的输入/历史快照与持久任务；事务提交后可以唤醒 worker，但可靠来源是数据库队列，不能仍依赖那一次 JVM event。
2. 幂等键由客户端每次“新的提交意图”生成、重试时复用，服务端以 actor、session、key 设唯一约束。相同 key 但不同请求摘要必须拒绝；不能只按问题文本去重。
3. 同会话冲突通过数据库唯一活动约束或可证明正确的串行策略处理。返回明确 409/排队状态，与游客现有“上一条仍在处理中”行为协调，不能只使用内存 Set。
4. Worker 用短事务领取、租约/心跳/fencing 防并发写回，模型调用不占用数据库事务。排队数、每用户活动数、超时和重试均有上限，数据库队列不能成为无限输入缓存。
5. 固定提交快照与 05 的 agentConfigHash；禁止在等待中静默切换为另一个 Agent 配置或模型。要么使用已保存且仍获授权的配置，要么因版本变化明确拒绝，本任务需选定并写测试。
6. 每次开始及后续关键步骤沿用 ResourceAccess / ScopedToolCallback 重新授权。账号停用、会话撤销、助手/KB 权限变化、模型停用都不能因已入队被绕过；必要验证信息仅存服务器内部。
7. 游客运行保存必要 owner 关联但不存/暴露原始 Cookie，继续仅注册 KnowledgeTool、getDate、directAnswer、terminate。管理员/账号工具配置不进入游客运行。
8. 排队任务取消可以直接原子终止；运行中取消为协作式停止，检查点之后不发新工具调用。已经发出的外部请求可能无法撤回，界面和文档不能声称已回滚其副作用。
9. 用 05 的服务更新事件和终态，过期 worker 不能追加最终答案覆盖新状态。SSE 只是通知，刷新从持久消息/运行记录恢复。
10. 崩溃恢复根据租约超时识别失联运行，不在多实例启动时把其他实例正在执行的工作全标为中断。QUEUED 可以恢复；RUNNING 超时后先 INTERRUPTED，保留已入库的消息和事件。

## 副作用边界

外部操作成功、数据库尚未记下结果时进程崩溃，不能仅凭本地“未完成”判断应该重试。默认方案是暴露中断状态，用户明确重新发起时形成新运行。若未来为某个工具增加自动重试，必须逐个证明服务商幂等键、结果查询或业务去重可用，不能统一重放所有工具。

模型调用和只读检索的重复一般仍可能增加费用；运行台账要如实记录已知尝试和未知结果，不把缺失 usage 填成 0。

## 测试与完成标准

- 真实数据库验证：消息事务回滚无任务；任务写入失败不返回成功；提交后重启仍能领取。
- 模拟同会话并发提交、相同幂等键重试、不同内容重用同 key、不同用户相同 key，结果符合定义。
- 两 worker 竞争、租约过期、迟到模型结果、取消与完成竞争不会产生第二个有效终态或重复最终消息。
- 故障注入在模型请求前后、工具调用前后、结果入库前后；运行中故障明确中断，不自动重发外部副作用。
- 账号权限/会话撤销与两访客隔离继续通过；游客/账号的界面、SSE 断连重连和历史来源仍正常。
- 回滚方案说明旧版执行器与新队列如何切换：暂停领取、排空/标记未完成任务、避免新旧 worker 双跑；数据库记录保留以便诊断。
- 交付给 08 的说明明确“可恢复排队、可识别中断”的边界，只有真正实现并验证的步骤级恢复才可写入 README。
