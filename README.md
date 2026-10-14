# ExoBrain

基于 Spring AI 的 Java Agent 系统。核心是一个 **Think-Execute 循环 + 状态机**：模型决定下一步动作，系统执行工具并回填结果，直到交付答复、发生错误或达到步骤上限。

技术栈：Java 21 · Spring Boot 3.5.8 · Spring AI 1.1.0 · PostgreSQL 17 + pgvector · MyBatis · React 19 + TypeScript + Ant Design

## 在线入口：游客免登录与账号密码登录

本轮前后端发布版本为 `20261014-md-preview`（2026-10-14），应用镜像为 `exobrain:20261014-md-preview`，两种访问模式同时保留：

| 模式 | 入口 | 访问范围 |
| --- | --- | --- |
| 游客免登录 | [公开体验](https://exobrain.sharmoon.cc.cd/) | 指定演示 Agent、公开知识库、当前浏览器的个人体验历史及只读示例对话 |
| 账号密码登录（管理模式） | [登录 / 管理入口](https://exobrain.sharmoon.cc.cd/admin/) | 经浏览器 HTTP Basic 认证后管理 Agent、知识库、文档和历史会话，并配置可选工具 |

登录模式目前由 **Nginx HTTP Basic** 提供账号密码认证。成功认证后进入同一管理工作区，可访问管理范围内的历史会话；它不包含自助注册、应用内账户中心或按登录账号隔离的数据空间。游客 Cookie 的历史隔离不限制管理员的管理权限。

- **个人体验历史**：消息保存在数据库，访客身份由 HttpOnly Cookie 识别。HTTPS 下 Cookie 使用 Secure、SameSite=Lax，有效期 30 天。同一 Cookie 可在刷新后继续读取历史；清除 Cookie、过期后获得新 Cookie 或更换浏览器，不能凭新身份找回原访客会话。历史不会自动变成公开内容。
- **示例对话**：[查看示例](https://exobrain.sharmoon.cc.cd/showcase) 展示使用公开资料实际执行、经审核后发布的问答、工具调用和结果，用于说明项目能力。示例为只读，原访客也不能继续发送、改名、删除或订阅其 SSE；体验同一问题需新建个人会话。
- **Agent 关联标识**：历史列表、对话顶部及示例页按会话保存的 `agentId` 匹配当前可见 Agent 名称，桌面与移动端均可查看。缺失关联、加载失败和不可见状态有独立提示，不回退为默认 Agent。名称不是历史配置快照；空关联的会话仍可读，但不能继续发送空 Agent ID 的消息。
- **工具范围**：游客执行器只注册 `KnowledgeTool`、`getDate`、`directAnswer`、`terminate`。登录管理模式可按 Agent 配置启用数据库查询、邮件等已注册可选工具，具体操作还依赖相应服务配置。管理端勾选不会把这些工具开放给游客，也不会因游客访问而被清空。

前端由同一源码构建公开版和管理版，分别使用公开受限 API 与经过认证的管理 API。构建、资源白名单、网关和更新步骤见 [部署说明](deploy/README.md#build)。

## 公开示例资料

公开知识库收录三份自写 Markdown，内容基于项目源码与官方技术资料，附有来源：

- [ExoBrain 项目导览](docs/public-demo/01_exobrain_overview.md)
- [RAG 检索原理](docs/public-demo/02_rag_principles.md)
- [Java 后端技术问答](docs/public-demo/03_java_backend_faq.md)

只有这三份文件用于索引。[检索测试清单](docs/public-demo/99_test_questions.md) 与 [演示助手提示词参考](docs/public-demo/agent-system-prompt.md) 供维护和复核，**不上传知识库**。公开示例仅使用上述项目资料，不包含私人聊天或运行凭据。

在知识库的文档列表点击 Markdown 文件名或“预览”入口，可以打开只读内容并切换“预览 / 源码”。支持 UTF-8 编码的 `.md`、`.markdown` 文件，扩展名不区分大小写，原文件最大 **2 MiB（2,097,152 字节）**。源码视图显示文件原文；这项阅读功能不改变现有的标题向量索引方式。

游客只能预览已列入公开名单的知识库文档。管理模式的预览沿用 HTTP Basic 认证，可通过原管理 API 或 `/admin/api/` 访问；文档原文接口不返回服务器存储路径。接口与部署要求见 [Markdown 预览说明](deploy/README.md#markdown-preview)。

---

## 它和「聊天机器人」差在哪

本项目的普通对话示例完成一次模型请求；Agent 运行时则循环执行“决策 → 工具执行 → 结果回填”。模型根据已获得的信息，决定继续取资料、使用工具，还是交付最终答复。

### 一次知识检索的流程示意

```text
用户询问项目实现
→ think：选择 KnowledgeTool，提供获准知识库 ID 与查询文本
→ execute：检索并回填实际资料片段，保存工具调用记录
→ think：依据片段组织回答，调用 directAnswer(answer)
→ execute：完成答复工具调用，结束本轮任务
```

这是流程示意，不是固定调用次数或预先写好的测试结果。真实执行记录可在公开精选页展开查看。源码中的 `CityTool` 返回固定城市、`WeatherTool` 返回模拟天气文本，不能用作实时定位或天气数据；它们在 `prod` profile 中关闭，也不在游客工具白名单内。`getDate` 则读取运行环境当前日期，线上时区为 `Asia/Shanghai`。

---

## 循环是怎么终止的

运行时支持以下结束路径：

| 路径 | 触发 | 代码位置 |
|---|---|---|
| **答复工具完成** | 模型通过 `directAnswer` 交付答复，工具执行后结束 | `think()` / `execute()` |
| **自然终止** | 模型这一轮没调工具，且已经产出面向用户的答复 | `think()` / `step()` |
| **主动终止** | 执行 `terminate`，并检查是否已经给出答复 | `execute()` |
| **兜底终止** | 达到 `MAX_STEPS = 20`，防止无限循环 | `run()` |

自然结束或 `terminate` 路径缺少答复时，会补一次收尾提醒；再次失败则置 `ERROR`。步骤上限用于限制执行长度，状态结束本身不代表答案正确或完整。

---

## 关键设计

### 1. 手动接管工具执行

```java
this.chatOptions = DefaultToolCallingChatOptions.builder()
        .internalToolExecutionEnabled(false)   // ← 关键
        .build();
```

Spring AI 的内部工具执行可以接管工具调用与结果回填。本项目关闭这一行为，由自己的运行时逐步执行，从而在每轮模型响应和工具结果之间插入记录与状态处理：

- 拿到**原始的、带 `toolCalls` 的**响应，能记录执行轨迹（`logToolCalls()`）
- **「是否继续」的判断权**回到自己手里
- 状态机 `AgentState` 能跟着走
- 消息持久化 + SSE 推送有了挂载点

这为扩展重试或中断提供了位置，但不代表所有工具都已实现中断和回滚。

### 2. 工具系统：声明 → 发现 → 分类 → 装配

- **声明**：实现 `Tool` 接口 + `@Component`，方法上标 `@Tool(name=..., description=...)`
- **发现**：`ToolFacadeServiceImpl` 构造器注入 `List<Tool>` —— Spring 把容器里所有实现 `Tool` 的 Bean 自动收集成 List。**没有注册表，发现靠集合注入**
- **分类**：按 `ToolType` 分成 `FIXED`（系统强制，如 `terminate`）/ `OPTIONAL`（按 Agent 配置下发）
- **装配**：`JChatMindFactory.resolveRuntimeTools()` = 全部 FIXED + 按该 Agent 的 `allowedTools` 挑 OPTIONAL
- **转换**：`MethodToolCallbackProvider` 把 `@Tool` 方法反射成 `ToolCallback[]`，在 `think()` 里交给模型

同一个工具可以有两套名字：

| 类 | `Tool.getName()`（系统侧名称） | `@Tool(name=)`（模型侧调用名称） |
|---|---|---|
| `KnowledgeTools` / 公开执行器的 `DemoKnowledgeTool` | `KnowledgeTool` | `KnowledgeTool` |
| `DateTool` | `dateTool` | `getDate` |
| `DataBaseTools` | `dataBaseTool` | `databaseQuery` |
| `EmailTools` | `emailTool` | `sendEmail` |

`Tool` 接口 + `ToolType` 用于管理、分类和配置；`@Tool` 注解 + `ToolCallback` 用于模型调用。`allowed_tools` 填系统侧的**可选工具**名称；固定工具无需填写。不匹配的可选名称会被跳过。

公开执行器有独立的四工具白名单。它清空的只是当前运行时配置副本中的可选工具列表，数据库中的管理端选择保持不变。公开知识检索还会再次验证知识库是否在发布名单内，权限不依赖提示词约束。

### 3. Agent 是数据，不是代码

`system_prompt`、`model`、`allowed_tools`、`allowed_kbs`、`chat_options` 都存在 `agent` 表里。通过登录管理模式可以创建、修改或删除 Agent，配置修改不需要重新构建代码。会话保存关联的 `agent_id`；删除 Agent 后，数据库会把该关联置空并保留会话历史。

### 4. 多模型注册表

```java
@Bean("deepseek-chat")  public ChatClient deepSeekChatClient(...)  { ... }
@Bean("glm-4.6")        public ChatClient zhiPuAiChatClient(...)   { ... }
```

`ChatClientRegistry` 注入 `Map<String, ChatClient>`，按名字取。接入新的模型客户端需要注册相应 Bean，并同步后端模型枚举、前端选项及服务配置；注册表存在不代表备用模型已配置可用。

注意：`agent.model` 里的字符串只是**查表用的 key**，跟真正发给 API 的模型名是解耦的 —— 后者来自 `spring.ai.*.chat.options.model` 配置。换模型名不用动 Java 代码。

### 5. RAG：PostgreSQL 一套体系管结构化数据和向量

`chunk_bge_m3` 保存章节正文和 1024 维向量。当前只对 Markdown（`.md` / `.markdown`）自动解析与索引：以任一级标题分段，**对标题生成向量，保存对应正文**。查询文本转成向量后，先按知识库 ID 限定范围，再用 `<->` 按 L2 距离取最多 3 个正文片段。

建表脚本配置 `ivfflat (embedding vector_l2_ops)`，与查询距离算子匹配；实际是否使用索引由数据库规划器决定。入库和查询应使用一致的嵌入模型与维度。其他格式解析、正文级索引和结构化来源返回尚未实现；本套示例在正文中标注文档名，供回答追溯来源。

---

## 可靠性处理与当前限制

以下机制分别处理答复交付、推送中断和模型繁忙等情况；执行流程结束与回答正确是不同的判断。

### 1. 区分决策输出与最终答复

包含检索等实际操作工具的决策轮会清空持久化的 `content`，保留工具调用信息。正式答复优先取 `directAnswer` 的 `answer` 参数。源码仍保留模型未按约定调用答复工具时的 `content` 回退，并记录警告；因此不能宣称已完全消除模型输出无关推理文本的可能。

### 2. SSE 推送中断与历史恢复

统一的 `trySend()` 捕获推送失败，避免单纯的连接中断终止 Agent。已经入库的消息可从历史接口重新读取；SSE 每 15 秒发送注释心跳，前端重连后刷新历史并按消息 ID 去重。

### 3. 检查是否有面向用户的答复

`userAnswerProduced` 记录是否产生了面向用户的答复。自然结束或 `terminate` 路径没有答复时，`forceFinalizeRound()` 只补一次收尾提醒，再次缺少答复时置 `ERROR`。这项检查不能替代对答案内容和工具实际结果的核对。

### 4. 识别模型返回的繁忙提示

对于模型在正常响应文本中返回的资源繁忙提示，运行时按特征词识别，最多尝试 3 次（首次加两次重试），间隔为 1 秒、2 秒。仍失败时进入错误处理并向前端报告执行失败；这不是自动切换模型的机制。

### 5. SSE 状态事件

运行时推送 `AI_THINKING`、`AI_EXECUTING`、`AI_GENERATED_CONTENT` 和 `AI_DONE`。`AI_PLANNING` 仍是预留事件类型，不表示已经实现单独的规划阶段。

**尚未实现**：自动切换备用模型、自助注册与应用层多用户账号隔离。登录管理入口与游客公开入口已经存在，不能把它们与上述未实现能力混为一谈。

---

## 快速开始

### 依赖

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 编译目标与部署运行时均为 21 |
| Maven | 使用仓库 Wrapper | 当前 Wrapper 为 3.9.11 |
| PostgreSQL | 17 + pgvector | 部署模板使用 `pgvector/pgvector:pg17` |
| Node.js | 22+ | 仅前端需要 |

### 1. 起数据库（pgvector）

以下是本地开发示例。先在当前终端环境提供 `POSTGRES_PASSWORD`，不要把密码写进源码或提交到 Git。

```bash
docker run -d --name jchatmind-postgres \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD -e POSTGRES_DB=jchatmind \
  -p 127.0.0.1:5433:5432 -v jchatmind_pgdata:/var/lib/postgresql/data \
  pgvector/pgvector:pg17
```

### 2. 建表

```bash
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind -v ON_ERROR_STOP=1 < db/jchatmind.sql
```

脚本会建 6 张表（`agent` / `chat_session` / `chat_message` / `knowledge_base` / `document` / `chunk_bge_m3`）、装 `vector` 扩展、建 ivfflat 索引，并插入一条默认 Agent。它用于新数据库初始化，不应作为已有数据库的升级脚本重复执行。

### 3. 配置并启动后端

按 [application.yaml](jchatmind/src/main/resources/application.yaml) 的结构，在本机 `jchatmind/src/main/resources/application-dev.yaml` 配置数据库连接、模型服务和嵌入服务。嵌入服务必须返回 1024 维向量。该开发文件已被模块 `.gitignore` 排除，打包规则也排除它；真实密码和 API Key 不应写入跟踪文件。

在仓库根目录使用 PowerShell，确认 `JAVA_HOME` 指向 JDK 21：

```powershell
Set-Location jchatmind
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

IDEA 的 Active profiles 同样填 `dev`。生产环境使用 `prod`，具体见 [部署说明](deploy/README.md)。

### 4. 起前端

另开终端，从仓库根目录运行：

```powershell
Set-Location ui
npm ci
npm run dev
```

默认本地开发使用管理功能的 `/api` 与 `/sse`，Vite 将请求代理到本机后端。公开体验还需配置演示 Agent 和资源发布名单；线上游客版与登录版应按 [双构建说明](deploy/README.md#build) 分别构建，不能只修改运行时环境变量来切换。

### 5. 用 curl 调（可选）

以下仅演示本机开发 API，使用前将占位符替换为真实的 Agent / 会话 ID。若要实时观察状态，先建立 SSE 连接再发消息；连接中断后仍可读取已保存的历史。线上原管理 API 需要 HTTP Basic 认证，游客只使用受限的 `/api/demo/` 与 `/sse/demo/` 路由。

```bash
# 建会话
curl -X POST localhost:8080/api/chat-sessions -H "Content-Type: application/json" \
  -d '{"agentId":"<id>","title":"test"}'

# 先挂 SSE 长连接（后台）
curl -N -s "http://localhost:8080/sse/connect/<chatSessionId>" > sse.log &

# 再发消息（role 必须小写 user）
curl -X POST localhost:8080/api/chat-messages -H "Content-Type: application/json" \
  -d '{"agentId":"<id>","sessionId":"<sid>","role":"user","content":"请调用日期工具告诉我今天的日期。"}'

# 看轨迹
curl -s "http://localhost:8080/api/chat-messages/session/<sid>"
rg -o '"type":"[A-Z_]*"' sse.log | sort | uniq -c
```

关键接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/chat-sessions` | 建会话，返回 `chatSessionId` |
| `POST` | `/api/chat-messages` | 发消息，异步触发 Agent |
| `GET` | `/api/chat-messages/session/{id}` | 完整消息历史（含 toolCalls / toolResponse） |
| `GET` | `/sse/connect/{chatSessionId}` | SSE 长连接，收状态与生成内容 |
| `GET` | `/api/agents` `/api/tools` | Agent 列表 / 可用工具列表 |
| `GET` | `/api/documents/{documentId}/content` | 管理模式读取 Markdown 原文；线上亦可通过 `/admin/api/documents/{documentId}/content` 访问 |
| `GET` | `/api/demo/documents/{documentId}/content` | 游客读取已公开知识库中的 Markdown 原文 |

两种原文接口均返回 `ApiResponse`，其 `data` 为 `{documentId, filename, content}`；`content` 是按 UTF-8 解码并移除可选 BOM 的文件文本。预览是只读操作，文件格式与大小限制对游客和管理模式均适用。

## 已上线版本的验证记录

2026-10-14 的 `20261014-md-preview` 发布已完成以下验证：

- 后端指定回归共 59 项：56 项通过，0 失败、0 错误，3 项因当前 Windows 环境不支持创建符号链接而跳过。实际发布 JAR 的存储实现另在隔离的 Linux Java 21 容器中通过 12 项检查，无跳过，覆盖符号链接等文件读取边界。
- 前端 lint、TypeScript 检查及公开版 / 管理版构建通过；本地浏览器用例覆盖 375px 布局、完整 2 MiB 源码、渲染安全、关闭与迟到响应、错误重试和管理操作入口。
- 候选网关与已发布网关的本机回环检查各通过 33 项；3 份公开文档经游客及认证管理 API 读取的文本均与磁盘原文一致，已有消息和私有运行配置保持不变。
- 公网 HTTPS 的 33 项预览检查及重新执行的 35 项公开演示回归均通过，覆盖原文一致性、禁止缓存、认证与公开名单、示例只读、游客隔离及私人历史拒绝。
- 真实 Chrome 中的游客与管理预览通过：原文渲染、源码与 API 文本逐字符一致、Escape 关闭和管理上传 / 删除入口均正常；390 × 844 游客手机视图无横向溢出，两种模式的控制台均无错误或警告。

本轮线上没有未公开文档实例，因此未现场验证实际私有文档的拒绝访问；该权限边界由后端测试覆盖。

同日较早发布的历史基线包含后端 34 项回归、游客 Cookie 隔离、知识检索、SSE、精选只读和管理认证；`20261014-agent-labels-r2` 另验证了不同 Agent 历史、空关联提示与禁发，以及手机布局。构建命令、当前发布验证与历史记录见 [部署说明](deploy/README.md#verification-record)。

---

## 目录结构

```
jchatmind/src/main/java/com/kama/jchatmind/
├── agent/
│   ├── JChatMind.java          # Agent 运行时：think / execute / step / run
│   ├── JChatMindFactory.java   # 按 Agent 配置装配运行时（工具 / 知识库 / 会话记忆）
│   ├── AgentState.java         # 状态机
│   ├── examples/               # 演进过程：V1 基础聊天 → V2 引入循环
│   └── tools/                  # 工具实现（知识库 / 数据库 / 邮件 / terminate）
├── config/                     # ChatClientRegistry、多模型注册、CORS、异步
├── controller/                 # REST + SSE 入口
├── demo/                       # 游客身份、公开资源白名单、独立执行器与精选记录
├── event/                      # ChatEvent + @Async 监听器（接口立即返回，Agent 后台跑）
├── service/                    # 会话 / 消息 / 知识库 / RAG
└── ...
ui/                             # React 19 + antd 前端
db/jchatmind.sql                # 建表脚本
deploy/                         # 双构建、容器与 Nginx 部署模板
docs/public-demo/               # 公开示例、检索测试与演示提示词
```

---

## 来源与许可

ExoBrain 基于 [JChatMind](https://github.com/youngyangyang04/JChatMind) 持续开发，Java 包名沿用 `com.kama.jchatmind`。项目保留上游作者程序员 Carl 的版权声明，并遵循 [MIT License](LICENSE)；使用和再分发时请保留原许可与版权声明。
