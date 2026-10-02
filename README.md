# ExoBrain

基于 Spring AI 的 Java Agent 系统。核心是一个 **Think-Execute 循环 + 状态机**：给模型一个目标，由它自己决定每一步做什么，系统负责执行工具并把结果回填，循环推进直到任务完成。

技术栈：Java 17 · Spring Boot 3.5 · Spring AI 1.1 · PostgreSQL + pgvector · MyBatis · React 19

---

## 它和「聊天机器人」差在哪

只差一个问题：**这次模型回复，是不是最终答案？**

- 聊天机器人 = 一次请求 → 一次回答
- Agent = 系统循环「决策 → 执行 → 反馈」，模型每一步**只决定下一步做什么**，不直接回答用户

这个项目里两者的差别就是一行：普通对话只有一次模型调用，而 Agent 走的是 `for (i < MAX_STEPS && state != FINISHED) step();`。

### 一次真实运行（数据库里落的消息轨迹）

用户输入「今天的天气怎么样？」：

```
1. [user]       今天的天气怎么样？
2. [assistant]  toolCalls: getCity{}, getDate{}          ← 一次返回两个工具调用
3. [tool]       getCity  → "深圳"
4. [tool]       getDate  → "2026-10-02"
5. [assistant]  toolCalls: weather{city:"深圳", date:"2026-10-02"}
                                                         ← 把前两个结果喂进下一个工具
6. [tool]       weather  → "深圳 2026-10-02：晴转多云，温度 25°C，湿度 60%"
7. [assistant]  「深圳今天晴转多云，气温 25°C…」          ← 最终回答，循环结束
```

实际执行了 **3 次 think + 2 次 execute**。第 5 条是关键：模型**自己**把上两步工具的结果当成了下一个工具的入参 —— 这就是「下一步由模型决定」。

---

## 循环是怎么终止的

三条路径，缺一不可：

| 路径 | 触发 | 代码位置 |
|---|---|---|
| **自然终止** | 模型这一轮没调工具 → `think()` 返回 false → 置 `FINISHED` | `think()` / `step()` |
| **主动终止** | 模型调用 `terminate` 工具 → `execute()` 检测到后置 `FINISHED` | `execute()` |
| **兜底终止** | 达到 `MAX_STEPS = 20`，防止无限循环 | `run()` |

另有第四道保障：**循环结束前必须确认用户真的拿到了答复**（见下方「已修复」第 3 条）。

---

## 关键设计

### 1. 手动接管工具执行

```java
this.chatOptions = DefaultToolCallingChatOptions.builder()
        .internalToolExecutionEnabled(false)   // ← 关键
        .build();
```

Spring AI 默认会自己「执行工具 → 回填结果 → 再问模型」一条龙跑完，然后只把最终文本给你。**代价是中间过程全被吞掉** —— 你拿到的响应里 `toolCalls` 已经是空的，不知道调了什么、调了几次、参数是什么。

关掉它，换来的是：
- 拿到**原始的、带 `toolCalls` 的**响应，能记录执行轨迹（`logToolCalls()`）
- **「是否继续」的判断权**回到自己手里
- 能插入中断 / 重试 / 回滚
- 状态机 `AgentState` 能跟着走
- 消息持久化 + SSE 推送有了挂载点

一句话：**自动执行 = 把循环交给框架；关掉 = 把循环拿回自己手里。**

### 2. 工具系统：声明 → 发现 → 分类 → 装配

- **声明**：实现 `Tool` 接口 + `@Component`，方法上标 `@Tool(name=..., description=...)`
- **发现**：`ToolFacadeServiceImpl` 构造器注入 `List<Tool>` —— Spring 把容器里所有实现 `Tool` 的 Bean 自动收集成 List。**没有注册表，发现靠集合注入**
- **分类**：按 `ToolType` 分成 `FIXED`（系统强制，如 `terminate`）/ `OPTIONAL`（按 Agent 配置下发）
- **装配**：`JChatMindFactory.resolveRuntimeTools()` = 全部 FIXED + 按该 Agent 的 `allowedTools` 挑 OPTIONAL
- **转换**：`MethodToolCallbackProvider` 把 `@Tool` 方法反射成 `ToolCallback[]`，在 `think()` 里交给模型

**同一个工具有两套名字，这是最容易踩的坑：**

| 类 | `Tool.getName()`（系统侧，`allowed_tools` 里写这个） | `@Tool(name=)`（模型侧，模型实际调用的） |
|---|---|---|
| `CityTool` | `cityTool` | `getCity` |
| `WeatherTool` | `weatherTool` | `weather` |
| `DataBaseTools` | `dataBaseTool` | `databaseQuery` |

`Tool` 接口 + `ToolType` 是给**人和系统**看的（管理、分类、配置）；`@Tool` 注解 + `ToolCallback` 是给**模型**看的。写 `allowed_tools` 时必须用系统侧名字，否则工具会被**静默跳过**、不报任何错。

### 3. Agent 是数据，不是代码

`system_prompt`、`model`、`allowed_tools`、`allowed_kbs`、`chat_options` 都存在 `agent` 表里。Agent 可以被创建、修改、禁用，**不需要改代码重新部署**。

### 4. 多模型注册表

```java
@Bean("deepseek-chat")  public ChatClient deepSeekChatClient(...)  { ... }
@Bean("glm-4.6")        public ChatClient zhiPuAiChatClient(...)   { ... }
```

`ChatClientRegistry` 注入 `Map<String, ChatClient>`，按名字取。加一个模型只需加一个 `@Bean`。

注意：`agent.model` 里的字符串只是**查表用的 key**，跟真正发给 API 的模型名是解耦的 —— 后者来自 `spring.ai.*.chat.options.model` 配置。换模型名不用动 Java 代码。

### 5. RAG：PostgreSQL 一套体系管结构化数据和向量

`chunk_bge_m3` 存切片与向量（bge-m3，1024 维），`similaritySearch` 用 `<->` 做 L2 距离检索，配 `ivfflat (embedding vector_l2_ops)` 索引。**索引算子类必须和查询算子匹配**，配 `vector_cosine_ops` 索引不会被用上。

---

## 已修复的可靠性问题

跑通项目后逐行核实代码，修掉了以下几类。这一节的共性主题是：**流程跑完不等于成功。**

### 1. 决策阶段的内部独白泄漏给用户

**现象**：用户看到模型的内心独白，比如「我来帮您查询今天的天气。首先需要获取当前的城市和日期信息。」

**原因**：`think()` 把决策阶段的原始 `content` 落库并以 `AI_GENERATED_CONTENT` 推给前端，UI 当正常聊天气泡渲染。而 Think 阶段的目标是**做决策，不是给用户回答** —— 两者走了同一条推送通道。

**修复**：引入「控制类工具 vs 干活类工具」的区分，决策轮落库时清空 `content` 且不下发，答复轮原样保留。

> ⚠️ 这里踩过一个坑：第一版判据是「有没有 `tool_calls`」，但实测存在 **「最终答复 + `terminate` 出现在同一条消息里」** 的情况 —— 那样会把真正的答案藏掉。正确判据是「有没有**真正干活的工具**」。这个 bug 读代码看不出来，只有把循环跑到 `terminate` 路径上才会暴露。

### 2. SSE 推送失败会中断整个 Agent 执行

**现象**：前端没连上 SSE、或连接中途断开，后端的 Agent 执行跟着崩。

**原因**：`think()` 落库后立刻调 `refreshPendingMessages()` → `sseService.send()`，找不到 emitter 时**抛异常**，异常冒到 `run()` 后整轮终止。

**修复**：抽出统一的 `trySend()`，推送失败只记 WARN。消息本身已落库，前端刷新即可从历史拿到 —— **可观测性不该拖垮主流程。**

### 3. 用户什么都没拿到，却算「成功结束」

**现象**（实测两种）：
- 模型查到数据后**直接调 `terminate`，一个字都不给用户**
- `content` 为空且没有 `tool_calls`

此时状态机走完、日志零 ERROR、`AI_DONE` 正常发出 —— **用户零收获**。

**原因**：终止判断只看「还有没有工具要调」，不看用户有没有真的拿到东西。

**修复**：引入 `userAnswerProduced` 标志 + `forceFinalizeRound()`。循环要结束但用户还没拿到答复时，注入一条提醒让模型补一轮收尾；**只补一次**，第二次仍失败则置 `ERROR`，让失败可见。

### 4. 上游的「资源繁忙」被当成正常回答

**现象**：模型服务过载时用 **HTTP 200 + 正常 `content`** 返回：

```
【资源繁忙通知】当前模型 [...] 请求量较大，暂无可用资源处理您的请求。请稍后重试…
```

Agent 把它当作合法答复落库并展示，日志零 ERROR —— **最危险的失败是「看起来成功的失败」**。

**修复**：按特征词识别 + 最多 3 次尝试 + 1s/2s 退避；仍失败则置 `ERROR` 并向前端推「执行失败」。

### 5. 补齐 SSE 状态事件

`SseMessage.Type` 定义了 5 种事件、前端为 5 种都写了处理逻辑，但后端只发 `AI_GENERATED_CONTENT` 一种 —— 状态推送实际未实现，前端那几个分支是死代码。现已补上 `AI_THINKING` / `AI_EXECUTING` / `AI_DONE`，数量与循环步数一致。

**尚未做**：自动切换备用模型。需要第二个真正可用的 provider 才有意义 —— 降级的前提是备用链路真的能通。

---

## 快速开始

### 依赖

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | Spring Boot 3.x 要求；本地用 JDK 21 |
| Maven | 3.6.3+ | |
| PostgreSQL | 14+ **且带 pgvector** | Windows 下建议用 docker |
| Node.js | 22+ | 仅前端需要 |

### 1. 起数据库（pgvector）

```bash
docker run -d --name jchatmind-postgres \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=123456 -e POSTGRES_DB=jchatmind \
  -p 5433:5432 -v jchatmind_pgdata:/var/lib/postgresql/data \
  pgvector/pgvector:pg17
```

### 2. 建表

```bash
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind -v ON_ERROR_STOP=1 < db/jchatmind.sql
```

脚本会建 6 张表（`agent` / `chat_session` / `chat_message` / `knowledge_base` / `document` / `chunk_bge_m3`）、装 `vector` 扩展、建 ivfflat 索引，并插入一条默认 Agent。

### 3. 配置模型 API Key

`src/main/resources/application-dev.yaml`（该文件已被 `.gitignore` 排除，密钥不会进版本库）：

```yaml
spring:
  ai:
    deepseek:
      api-key: <你的 key>
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
```

启动时激活 `dev` profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

> IDEA：Run Configuration → Active profiles 填 `dev`

### 4. 起前端

```bash
cd ui && npm install && npm run dev     # http://localhost:5173
```

前端直连 `http://localhost:8080`，跨域由后端 `CorsConfig` 放开 `localhost:*`。

### 5. 用 curl 调（可选）

> ⚠️ **顺序很重要**：必须先开 SSE 长连接，再发消息。UI 就是这么做的。

```bash
# 建会话
curl -X POST localhost:8080/api/chat-sessions -H "Content-Type: application/json" \
  -d '{"agentId":"<id>","title":"test"}'

# 先挂 SSE 长连接（后台）
curl -N -s localhost:8080/sse/connect/<chatSessionId> > sse.log &

# 再发消息（role 必须小写 user）
curl -X POST localhost:8080/api/chat-messages -H "Content-Type: application/json" \
  -d '{"agentId":"<id>","sessionId":"<sid>","role":"user","content":"今天的天气怎么样？"}'

# 看轨迹
curl -s localhost:8080/api/chat-messages/session/<sid>
grep -o '"type":"[A-Z_]*"' sse.log | sort | uniq -c
```

关键接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/chat-sessions` | 建会话，返回 `chatSessionId` |
| `POST` | `/api/chat-messages` | 发消息，异步触发 Agent |
| `GET` | `/api/chat-messages/session/{id}` | 完整消息历史（含 toolCalls / toolResponse） |
| `GET` | `/sse/connect/{chatSessionId}` | SSE 长连接，收状态与生成内容 |
| `GET` | `/api/agents` `/api/tools` | Agent 列表 / 可用工具列表 |

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
├── event/                      # ChatEvent + @Async 监听器（接口立即返回，Agent 后台跑）
├── service/                    # 会话 / 消息 / 知识库 / RAG
└── ...
ui/                             # React 19 + antd 前端
db/jchatmind.sql                # 建表脚本
```

---

## License

MIT
