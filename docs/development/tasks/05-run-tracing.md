# 05 — 真实执行追踪

**优先级：第二阶段。前置：04 的运行时/消息变更稳定。消费者：07、08。**

## 可直接分发的提示词

> 请执行 ExoBrain 开发任务 05。先阅读 docs/development/README.md、docs/development/contracts.md 和 docs/development/tasks/05-run-tracing.md。在现有工具调用记录上增量实现持久化运行摘要与执行事件，记录真实配置标识、调用耗时、重试和可获取的模型用量，并在聊天页按需展示。保持私有会话隔离与公开精选脱敏，旧消息没有数据时不补造。此任务负责观测，不承诺任务可恢复调度；把稳定的 runId/事件接口交给 07。交付实际验证和 docs/development/deliveries/05.md。

## 代码入口与范围

- [JChatMind](../../../jchatmind/src/main/java/com/kama/jchatmind/agent/JChatMind.java)：模型重试、工具调用、消息入库、结束路径。
- [JChatMindFactory](../../../jchatmind/src/main/java/com/kama/jchatmind/agent/JChatMindFactory.java)：实际配置、模型和运行时工具装配。
- [ChatEvent](../../../jchatmind/src/main/java/com/kama/jchatmind/event/ChatEvent.java)、[ChatEventListener](../../../jchatmind/src/main/java/com/kama/jchatmind/event/listener/ChatEventListener.java)、[DemoExecutionService](../../../jchatmind/src/main/java/com/kama/jchatmind/demo/DemoExecutionService.java)：运行创建/启动入口。
- [SseMessage](../../../jchatmind/src/main/java/com/kama/jchatmind/message/SseMessage.java)、SseService、聊天前端与共享 metadata。
- 本任务负责新的运行/事件 repository、DTO、服务、受权读取 API、带 `05` 编号的迁移、执行详情 UI。
- 不实现新的 Agent 框架、提示词调试后台、队列调度或完整分布式 tracing 平台。

## 实施要求

1. 每次提交生成服务端 runId，与 sessionId、triggeringMessageId 和最终答复关联。记录本次实际使用的 Agent 名称、配置 hash、模型目录 ID/实际模型名，避免后续改名把历史运行解释成另一配置。
2. 按共享契约落库摘要与顺序事件，已有 toolCalls/toolResponse 保留。助手完整配置/历史快照如果为后续恢复而存储，只能留在受限服务端记录，不能透传到公开 trace。
3. 在真正的模型请求和工具边界计时；多工具调用各有自己的 toolCallId。不能把一整轮耗时平均分给每个工具，也不能把模型计划调用记录成已完成工具。
4. 模型繁忙重试每次记录 attempt、开始/结束和安全结果分类。日期查询、检索等只有真实执行才出现完成事件；失败/权限撤销也有明确终态。
5. 能从服务商读取 usage 时保存实际值；没有返回时为 null/“未提供”。不用字符数伪造精确 token，也不默认每个失败请求用量为零。
6. 持久化事件与 SSE 发送分开处理：SSE 中断不取消本次执行，刷新按数据库记录恢复；消息/事件按稳定 ID 去重。
7. 事件顺序由服务端生成，状态只能按允许的迁移更新，终态不会被迟到的 RUNNING 覆盖。05 不存在可靠领取租约时，重启遗留的 RUNNING 要呈现未知/中断限制，不能一直伪装正在执行。
8. 记录失败策略需明确：主记录创建失败不能显示为成功启动；中途事件持久化失败应保留已有记录并标明数据不完整，不捏造完整时间线。07 之后复用这些边界，不重写整个模型循环。
9. 日志/界面只记录可核验动作，不记录模型私有推理内容、API Key、授权令牌/哈希、内部连接信息或其他用户文档。

## 读取与界面

- 按共享契约实现会话运行列表/单次运行读取，所有路径重新验证会话所有权。管理员不获得“查看所有用户运行”的隐式权限。
- 游客仅能读自己的访客运行；公开精选展示前再次按公开来源和字段白名单处理。新游客路由由 08 配合更新 Nginx，不能临时放开全部 `/api/demo/*`。
- 默认只显示运行结果、助手/模型名称与耗时；展开后看到模型调用、检索、工具、重试时间线，以及 04 的引用。
- 排队耗时和执行耗时分开；失败要显示可理解的安全原因。旧记录没有 trace 时显示“此记录未保存执行详情”，不要用当前配置或浏览器计时补齐。
- API/SSE 支持新增字段可选，保留两种前端构建、历史恢复与移动端布局。

## 验证与完成标准

- 固定模型/工具夹具覆盖正常、无工具问候、工具失败、模型两次重试后成功、达到步数上限、授权撤销和无答复结束。
- 每个实际调用与事件一一对应；工具结果、引用、运行 ID 可关联；没有负耗时、伪用量、重复终态。
- 数据库重读、浏览器刷新和 SSE 重连后仍显示同一批记录。敏感字段不出现在 API、SSE 或公开日志。
- 两用户、管理员、两个访客之间的 runId 访问矩阵全部覆盖。
- 报告测量记录事件的开销、事件保留/清理策略与重启限制；没有做过的性能测试不写数字。
- 向 07 交付 run 状态存储、事件追加、幂等终态更新接口及扩展位置；向 08 交付新 API、迁移、网关变更和公开字段清单。
