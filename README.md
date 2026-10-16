# ExoBrain

基于 Spring AI 的 Java Agent 系统。核心是一个 **Think-Execute 循环 + 状态机**：模型决定下一步动作，系统执行工具并回填结果，直到交付答复、发生错误或达到步骤上限。

技术栈：Java 21 · Spring Boot 3.5.8 · Spring AI 1.1.0 · PostgreSQL 17 + pgvector · MyBatis · React 19 + TypeScript + Ant Design

## 在线入口：游客免登录与账号密码登录

| 模式 | 入口 | 访问范围 |
| --- | --- | --- |
| 游客免登录 | [公开体验](https://exobrain.sharmoon.cc.cd/) | 指定演示 Agent、公开知识库、当前浏览器的个人体验历史及只读示例对话 |
| 普通用户登录 | [账号登录](https://exobrain.sharmoon.cc.cd/admin/) | 自己的 Agent、知识库、文档与会话；可使用公开助手和只读公开资料 |
| 管理员登录 | [账号登录](https://exobrain.sharmoon.cc.cd/admin/) | 管理用户、系统 LLM / Embedding 模型及公开演示；私有知识库、助手和会话仍按账号隔离 |

账号模式使用应用内登录页和服务端会话，可退出登录与修改自己的密码。管理员可以创建账号、调整角色、启停账号和重置密码；停用、改密或变更角色会撤销已有登录。所有账号的私有资源和历史相互独立，管理员角色也不能列出、预览、检索、修改或删除其他账号的私有知识库，不能通过其助手或会话绕过隔离。公开助手和知识库在普通用户工作区中只读。当前账号由管理员创建，不提供自助注册。

新建知识库默认仅当前账号可见。“我的知识库”与“公开演示库”分开展示；新建、上传和聊天不会把私人资料自动发布。共享同一登录账号的使用者属于同一数据空间，需要互相隔离时应使用不同账号。

知识库详情提供“删除知识库”及二次确认，删除该库的文档、检索索引和模型绑定，并解除助手引用。原文件在数据库提交后清理，临时失败会进入持久队列重试；删除事务失败时保留原数据和文件。已有聊天记录不随知识库一起删除。

- **个人体验历史**：消息保存在数据库，访客身份由 HttpOnly Cookie 识别。HTTPS 下 Cookie 使用 Secure、SameSite=Lax，有效期 30 天。同一 Cookie 可在刷新后继续读取历史；清除 Cookie、过期后获得新 Cookie 或更换浏览器，不能凭新身份找回原访客会话。历史不会自动变成公开内容。
- **示例对话**：[查看示例](https://exobrain.sharmoon.cc.cd/showcase) 展示使用公开资料实际执行、经审核后发布的问答、工具调用和结果，用于说明项目能力。示例为只读，原访客也不能继续发送、改名、删除或订阅其 SSE；体验同一问题需新建个人会话。
- **Agent 关联标识**：历史列表、对话顶部及示例页按会话保存的 `agentId` 匹配当前可见 Agent 名称，桌面与移动端均可查看。缺失关联、加载失败和不可见状态有独立提示，不回退为默认 Agent。名称不是历史配置快照；空关联的会话仍可读，但不能继续发送空 Agent ID 的消息。
- **工具范围**：游客执行器只注册 `KnowledgeTool`、`getDate`、`directAnswer`、`terminate`；知识检索同时受当前账号权限和助手获准的知识库范围限制。普通用户不能启用管理工具；管理员可配置已注册的邮件等可选工具。没有账号隔离的数据库查询、文件系统示例工具禁止进入账号执行器，生产环境不注册通用 SQL 查询工具。

前端由同一源码构建游客版和账号版，账号版按角色显示管理入口。接口在后端校验登录、角色、资源归属和写请求验证，不依赖菜单是否可见。构建、资源白名单、网关和更新步骤见 [部署说明](deploy/README.md#build)。

管理员可在“系统模型”新增、编辑、启停和测试系统 LLM / Embedding 模型；新建助手从已启用的 LLM 列表选择。API Key 加密存储且不在读取接口中返回。每个知识库绑定自己的 Embedding 模型，切换默认值只影响新知识库；已有绑定禁止直接变更模型名、协议或维度，避免混用向量。当前向量维度为 1024。

## 公开示例资料

公开知识库收录三份自写 Markdown，内容基于项目源码与官方技术资料，附有来源：

- [ExoBrain 项目导览](docs/public-demo/01_exobrain_overview.md)
- [RAG 检索原理](docs/public-demo/02_rag_principles.md)
- [Java 后端技术问答](docs/public-demo/03_java_backend_faq.md)

只有这三份文件用于索引。[检索测试清单](docs/public-demo/99_test_questions.md) 与 [演示助手提示词参考](docs/public-demo/agent-system-prompt.md) 供维护和复核，**不上传知识库**。公开示例仅使用上述项目资料，不包含私人聊天或运行凭据。

在知识库的文档列表点击 Markdown 文件名或“预览”入口，可以打开只读内容并切换“预览 / 源码”。支持 UTF-8 编码的 `.md`、`.markdown` 文件，扩展名不区分大小写，原文件最大 **2 MiB（2,097,152 字节）**。源码视图显示文件原文；这项阅读功能不改变现有的标题向量索引方式。

游客只能预览已列入公开名单的知识库文档。登录后可以预览自己有权访问的文档，接口为 `/api/` 或 `/admin/api/`，均校验服务端会话和知识库权限；文档原文接口不返回服务器存储路径。接口与部署要求见 [Markdown 预览说明](deploy/README.md#markdown-preview)。

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

`system_prompt`、`model`、`allowed_tools`、`allowed_kbs`、`chat_options` 都存在 `agent` 表里。账号登录后可按资源权限创建、修改或删除 Agent，配置修改不需要重新构建代码。会话保存关联的 `agent_id`；删除 Agent 后，数据库会把该关联置空并保留会话历史。

### 4. 系统模型目录

`ChatClientRegistry` 根据 `agent.model` 保存的目录 ID，读取数据库中的已启用 LLM 配置，再由 `ModelRuntimeService` 创建实际客户端。目录 ID 和发给服务商的模型名独立；管理员可在“系统模型”配置 DeepSeek、OpenAI 兼容接口及智谱，无需修改前端选项或重新编译。模型被停用或不存在时会明确拒绝运行，不回退到旧的静态客户端。

首次启动导入已有服务配置，后续以数据库中的管理配置为准。API Key 使用独立持久密钥进行 AES-GCM 加密，读取接口仅返回是否已配置。Embedding 支持 OpenAI 兼容接口和 Ollama，每个知识库固定绑定一个 1024 维模型；切换默认值只影响新知识库。已绑定模型更换服务地址必须保持相同模型和向量空间，换模型或供应商时应新建模型与知识库并重新导入资料。

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

**尚未实现**：自动切换备用模型和自助注册。账号、角色、普通用户资源隔离以及系统模型管理已实现。

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
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind -v ON_ERROR_STOP=1 < deploy/migrations/20261014-accounts.sql
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind -v ON_ERROR_STOP=1 < deploy/migrations/20261014-models.sql
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind -v ON_ERROR_STOP=1 < deploy/migrations/20261016-storage-cleanup.sql
```

基础脚本创建业务表、`vector` 扩展、索引和默认 Agent；迁移补充账号、会话、资源归属、模型目录和文件清理队列。已有数据库升级时只执行尚未应用的迁移，不重复执行带默认数据的基础脚本。

### 3. 配置并启动后端

按 [application.yaml](jchatmind/src/main/resources/application.yaml) 的结构，在本机 `jchatmind/src/main/resources/application-dev.yaml` 配置数据库连接、模型服务和嵌入服务。嵌入服务必须返回 1024 维向量。该开发文件已被模块 `.gitignore` 排除，打包规则也排除它；真实密码和 API Key 不应写入跟踪文件。

另在仓库外准备管理员初始密码文件，以及内容为 32 个随机字节的 Base64 文本的模型加密密钥文件，在开发配置中设置：

```yaml
app:
  auth:
    public-origin: http://localhost:5173
    secure-cookie: false # 仅本机 HTTP 开发；HTTPS 部署使用 true
    bootstrap-admin-username: admin
    bootstrap-admin-password-file: /absolute/private/path/admin-password
  models:
    encryption-key-file: /absolute/private/path/models-encryption-key
```

初始密码仅在账号表为空时用于创建管理员。首次启动会把现有模型配置导入加密模型目录，并给旧知识库绑定原 Embedding；后续在界面维护模型，重启不会覆盖修改。请保留并备份加密密钥。

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

以下仅演示本机开发 API。先准备包含 `username`、`password` 的私有 `login.json`，登录后保留 Cookie，并从响应 `data.csrfToken` 读取写请求验证值到 `CSRF_TOKEN`。登录文件和 Cookie 文件应放在仓库外。使用前替换真实的 Agent / 会话 ID；游客继续使用受限的 `/api/demo/` 与 `/sse/demo/`。

```bash
# 登录并保存 Cookie；响应包含 data.csrfToken
curl -c cookies.txt -X POST localhost:8080/api/auth/login \
  -H "Content-Type: application/json" --data-binary @login.json

# 建会话
curl -b cookies.txt -X POST localhost:8080/api/chat-sessions \
  -H "Content-Type: application/json" -H "X-CSRF-Token: $CSRF_TOKEN" \
  -d '{"agentId":"<id>","title":"test"}'

# 先挂 SSE 长连接（后台）
curl -b cookies.txt -N -s "http://localhost:8080/sse/connect/<chatSessionId>" > sse.log &

# 再发消息（role 必须小写 user）
curl -b cookies.txt -X POST localhost:8080/api/chat-messages \
  -H "Content-Type: application/json" -H "X-CSRF-Token: $CSRF_TOKEN" \
  -d '{"agentId":"<id>","sessionId":"<sid>","role":"user","content":"请调用日期工具告诉我今天的日期。"}'

# 看轨迹
curl -b cookies.txt -s "http://localhost:8080/api/chat-messages/session/<sid>"
rg -o '"type":"[A-Z_]*"' sse.log | sort | uniq -c
```

关键接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` / `GET` | `/api/auth/login` / `/api/auth/me` | 登录与当前账号身份 |
| `POST` / `PATCH` | `/api/auth/logout` / `/api/auth/password` | 退出与修改自己的密码 |
| `GET` / `POST` / `PATCH` | `/api/admin/users` / `/api/admin/users/{id}` | 管理员维护账号 |
| `GET` | `/api/models` | 当前账号可选的启用模型，不含凭据 |
| `GET` / `POST` / `PATCH` / `DELETE` | `/api/admin/models` / `/api/admin/models/{id}` | 管理员维护系统模型 |
| `POST` | `/api/admin/models/{id}/test` | 管理员测试模型连接 |
| `GET` / `POST` / `DELETE` | `/api/knowledge-bases` / `/api/knowledge-bases/{id}` | 按账号列出、创建和删除知识库；列表以 `publicResource` 区分公开演示资料 |
| `POST` | `/api/chat-sessions` | 建会话，返回 `chatSessionId` |
| `POST` | `/api/chat-messages` | 发消息，异步触发 Agent |
| `GET` | `/api/chat-messages/session/{id}` | 完整消息历史（含 toolCalls / toolResponse） |
| `GET` | `/sse/connect/{chatSessionId}` | SSE 长连接，收状态与生成内容 |
| `GET` | `/api/agents` `/api/tools` | Agent 列表 / 可用工具列表 |
| `GET` | `/api/documents/{documentId}/content` | 登录后按资源权限读取 Markdown 原文；线上亦可通过 `/admin/api/documents/{documentId}/content` 访问 |
| `GET` | `/api/demo/documents/{documentId}/content` | 游客读取已公开知识库中的 Markdown 原文 |

两种原文接口均返回 `ApiResponse`，其 `data` 为 `{documentId, filename, content}`；`content` 是按 UTF-8 解码并移除可选 BOM 的文件文本。预览是只读操作，文件格式与大小限制对游客和账号模式均适用。


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
├── auth/                       # 登录会话、角色、资源归属与运行时权限
├── config/                     # ChatClientRegistry、CORS、异步
├── controller/                 # REST + SSE 入口
├── demo/                       # 游客身份、公开资源白名单、独立执行器与精选记录
├── event/                      # ChatEvent + @Async 监听器（接口立即返回，Agent 后台跑）
├── models/                     # 系统模型目录、密钥加密、动态客户端与知识库绑定
├── service/                    # 会话 / 消息 / 知识库 / RAG
└── ...
ui/                             # React 19 + antd 前端
db/jchatmind.sql                # 建表脚本
deploy/                         # 双构建、容器与 Nginx 部署模板
docs/public-demo/               # 公开示例、检索测试与演示提示词
docs/development/               # 优化开发指南、共享契约与可分发任务书
```

---

## 开发指南

[优化开发指南](docs/development/README.md) 按当前实现拆分了并发修复、检索评测、正文索引、可核验引用、执行追踪和持久化任务，提供共享接口契约、任务依赖、代码入口与验收标准。文档中的优化项是待实施方案，已上线能力以本 README 和部署说明为准。

[主 Agent 开发交接单](docs/development/orchestrator.md) 提供统一启动提示词，由主 Agent 自行调用子 Agent，按依赖组织实现、审查、集成和交付。

---

## 来源与许可

ExoBrain 基于 [JChatMind](https://github.com/youngyangyang04/JChatMind) 持续开发，Java 包名沿用 `com.kama.jchatmind`。项目保留上游作者程序员 Carl 的版权声明，并遵循 [MIT License](LICENSE)；使用和再分发时请保留原许可与版权声明。
