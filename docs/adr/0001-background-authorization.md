# ADR 0001 — 后台工作线程中的权限传播

- 状态：已实现（决策已在当前代码中落地）
- 范围：账号工作区的异步执行（游客入口另有自己的策略，见下文）
- 相关契约：[共享契约](../development/contracts.md) §1（账号校验入口 `ResourceAccess`、`ScopedToolCallback`）与 §5（执行期间停用账号、撤销会话、删除资源必须停止后续调用）
- 相关位置：`auth/AuthFilter.java`、`auth/AuthContext.java`、`auth/AuthService.currentActor`、`auth/ResourceAccess.java`、`auth/ScopedToolCallback.java`、`event/ChatEvent.java`、`event/listener/ChatEventListener.java`、`agent/JChatMindFactory.createForSubmission`、`service/impl/ChatMessageFacadeServiceImpl.createChatMessage`、`auth/AuthenticatedStreams.java`

## 1. 问题

账号的一次问答在 HTTP 请求返回之后才真正执行：

- `POST /api/chat-messages` 在请求线程里完成授权、写入用户消息、发布 `ChatEvent`，随即返回 `chatMessageId`；
- `ChatEventListener.handle` 用 `@Async`（`taskExecutor`，core 4 / max 10 / queue 100）在**另一个线程**里跑 `JChatMind` 的多轮 think/execute 循环；
- 请求线程的身份放在 `AuthContext` 的 `ThreadLocal` 里，并会在请求结束时 `finally` 清理。

由此产生四个必须回答的问题：

1. 执行线程没有请求上下文，**凭什么身份**访问会话、助手、知识库与模型？
2. 模型可能调用多次工具，执行期间账号被停用、角色被改、会话被撤销、知识库被删除或授权被修改时，**在哪里生效**？
3. 模型给出的知识库 ID（`KnowledgeTool(kbsId, query)` 的参数）是**客户端/模型可控输入**，如何保证它不越出助手获准范围？
4. 失败时如何既让用户看到失败、又不把内部信息（栈、身份、会话标识）泄漏出去？

约束：不得把权限放进提示词或依赖前端隐藏按钮；不得假设请求线程身份自动存在；后台线程不得冒用管理员身份。

## 2. 考虑过的方案

| 方案 | 做法 | 否决原因 |
| --- | --- | --- |
| A. 执行线程重新登录/重读当前会话 | 在执行线程里按 sessionId 反查会话或重新认证 | 执行线程没有请求上下文；"当下"的身份与提交时的意图不同；仍然无法表达"执行中途失效"；也无法防止历史快照在执行期间被新消息污染 |
| B. 事件携带 `AuthUser` 对象（含角色）直接使用 | 一次性把用户对象放进事件，执行时不再查库 | 角色、启用状态、会话撤销在执行期间不再生效；长生命周期对象中持有身份实体易被误用 |
| C. 让线程池继承请求线程的 `ThreadLocal`（`InheritableThreadLocal` / 任务装饰器） | 线程池内看上去"像请求线程" | 线程池复用身份会导致串线（正是本批次要消除的一类共享状态）；一旦账号失效，线程内的身份仍然"看起来有效"；隐式权威难以审计 |
| D. 只在 Controller 校验一次，运行时不再复核 | 依赖"提交即授权" | 多轮工具调用可能持续很久，期间账号/会话/授权可能变化；契约明确要求权限变化后不再继续调用 |
| E. 用系统（管理员）身份在后台运行，用提示词约束范围 | 后台统一以系统身份执行 | 把安全边界交给模型；管理员身份会绕过账号隔离；直接违反产品边界 |
| F. 身份快照 + 显式复核 + 每次工具调用前重跑守卫 | 见下一节 | **采用** |

## 3. 最终选择

1. **事件携带不可变身份快照**：`ChatMessageFacadeServiceImpl.createChatMessage` 在请求线程取 `AuthContext.requireSession()`，把 `actorId`、`authVersion`、`tokenHash`、`triggeringMessageId` 与序列化后的历史快照一起放进 `ChatEvent`。请求线程不把 `ThreadLocal` 传给后台线程。
2. **执行线程不设置 `ThreadLocal` 权威**：`ResourceAccess` 的后台路径全部显式接收快照参数（`requireExecutionActor(...)`、`validateRuntime(...)`），在后台路径上调用 `AuthContext.requireSession()` 只会得到未授权错误（`AuthException`），不会"继承"到任何身份——执行线程的身份必须由调用方显式传入。
3. **复核链**（`ResourceAccess.validateRuntime` → `requireExecutionActor`）：
   - `AuthService.currentActor(userId, authVersion, tokenHash)`：token 哈希必须是 64 位十六进制；数据库会话必须存在且未过期；账号必须启用；会话的 `auth_version` 必须等于提交时的版本；userId 与 tokenHash 必须与会话记录一致（`AuthRepository.findSession` 同时以 `u.enabled AND s.auth_version = u.auth_version` 过滤）。
   - 会话可读、会话的 `agentId` 必须等于本次运行的助手、助手必须可读。
   - `validateAgentConfiguration`：助手获准的每个知识库逐个 `requireRead`；普通用户不得使用管理类工具；数据库/文件系统工具在账号运行时一律拒绝；模型必须处于启用状态。
4. **历史快照代替实时历史**：`JChatMindFactory.createForSubmission` 用快照恢复记忆（最多 101 条、按 `messageLength` 截断），并校验快照非空、每条都属于本会话、最后一条正是触发消息且内容一致。执行期间新写入的消息不会混入本次运行。
5. **每个工具调用前重跑守卫**：`JChatMindFactory.createRuntime` 在装配工具时把 `ToolCallback` 逐层包成 `ScopedToolCallback(delegate, allowedKbs, runtimeGuard)`；`runtimeGuard` 就是上述复核闭包。除重跑守卫外，`ScopedToolCallback` 还针对 `KnowledgeTool` 校验参数：`kbsId` 必须是规范 UUID 且属于助手获准集合，否则拒绝（模型不能自选知识库）。
6. **会话标识与生命周期**：订阅侧 `SseController` 先 `requireSession`，`AuthenticatedStreams` 记录 emitter→tokenHash，每 15 秒查库确认会话仍有效，撤销/过期即关闭连接（`fail closed`：查库异常也关闭）。
7. **失败语义**：`AuthException` 表示授权已失效 → 静默终止（不推送、不泄漏）；其他异常 → 先 `requireExecutionActor` 复核 actor，复核通过才推送 `AI_DONE` 失败状态，复核失败则同样静默。
8. **游客路径不复用该机制**：游客没有账号身份，其边界是 `DemoAccessPolicy`（发布白名单 + 每会话一个在内存中的运行 + 固定四工具白名单 + `DemoKnowledgeTool` 每次执行再校验 `kbId`）。两个入口的隔离模型在文档与测试中分别说明。

## 4. 代价与已知限制

- 每次工具调用都会查库复核（会话、账号、归属、助手配置）；换取"权限变化在调用前生效"。这是有意的取舍：正确性优先于吞吐。
- 快照中包含会话哈希与历史消息正文，会在执行期间短暂驻留内存；因此日志不打印这些值，错误响应不返回原始异常与内部标识，客户端只看到固定状态文案。
- 执行中途失效只能**在下一个检查点**停止，且**不回滚**已经发生的模型或工具调用；本 ADR 不承诺任何回滚或 exactly-once 语义。
- `ScopedToolCallback` 的参数白名单目前只覆盖 `KnowledgeTool`；新增"模型可传资源 ID"的工具时必须同时实现同类参数校验，否则会留下越权入口。
- 历史快照最多 101 条、并按助手配置截断，超长会话只带近期窗口。
- 权限复核依赖数据库可用；数据库不可用时后台执行会失败（静默），不会降级为"无校验运行"。
- 该机制**不**使对话任务可恢复：进程重启仍会丢失正在执行的运行（属于任务 07 的边界）。

## 5. 验证

- 单元测试（默认 `-Punit` profile，`scripts/check.sh --unit` / `scripts/check.ps1 -Unit` 运行）：`AuthFilterTest`、`ResourceAccessTest`、`AuthenticatedExecutionSessionTest`、`ChatSubmissionSnapshotTest`、`ScopedToolCallbackTest`、`AuthListenerFailureTest`、`AuthServiceTest`、`DemoControllerTest` 等，覆盖会话/CSRF/管理员前缀、归属判定、快照与触发消息一致性、助手 KB 白名单、监听器失败路径。
- 真实 PostgreSQL 集成（`-Ppostgres-it`）验证的是数据库结构、应用角色授权与知识库作用域，**不是**本 ADR 的端到端传播；端到端权限矩阵（两个普通用户、管理员、两个游客）在集成分批执行并记录在对应交付记录中。
- 本轮的实测数字见 [任务 08 交付记录](../development/deliveries/08.md)（含选择器、通过/跳过计数与未执行项）。

## 6. 何时重新评估

- 引入持久化对话任务/队列（任务 07）：需要重新决定"提交与入队原子化""重启后领取""崩溃标记 `INTERRUPTED`"如何与身份快照和版本检查共存；届时新增 ADR，而不是修改本条目。
- 若执行改为跨进程 worker 或需要长时间排队：`auth_version`/`tokenHash` 的检查点、排队期间账号变更的处理（拒绝还是排队时重新授权）需要重新设计。
- 若新增可传入资源 ID 的工具、或引入跨会话/跨知识库的组合检索：必须先扩展参数级校验与缓存键，再评估本 ADR 是否仍足够。
- 若会话校验成为性能瓶颈：可以引入有界缓存，但缓存键必须包含账号、会话哈希与 `auth_version`，且授权变化后不得继续命中。
