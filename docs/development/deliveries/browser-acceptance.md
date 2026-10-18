# 浏览器交互验收（2026-10-18）

被测对象：提交 `a6bc0ae` 的**已提交代码**（前端 `ui/` 用 `git archive HEAD` 导出后单独跑，后端用同一提交构建的
`jchatmind-0.0.1-SNAPSHOT.jar`）。真实 PostgreSQL + 真实 Embedding（`BAAI/bge-m3`，1024 维）+ 真实对话模型
（`DeepSeek-V4-Flash-0731`，metapi），跑在一次性库 `exobrain_eval02` 上。

> 工作目录里还有一份**未提交**的 UI 主题改动（`ui/src/**`、`ui/tailwind.config.js`，属于他人/其它会话的工作）。
> 本次验收刻意不包含它：前端用 HEAD 的导出副本启动，不修改、不提交那份改动。

## 1. 环境与夹具

| 项目 | 值 |
| --- | --- |
| 前端 | `vite` 开发服务器 `http://127.0.0.1:5199`（HEAD 导出 + 仓库 `ui/node_modules` 的目录联接） |
| 后端 | 同提交 jar，`http://127.0.0.1:8080`，`app.auth.public-origin=http://127.0.0.1:5199` |
| 账号 | `eval02`（USER 角色，会话 cookie + CSRF） |
| 助手 | `eval-assistant`（`3acc45f6-e49c-4690-abf4-38a0fd62e004`），模型 `deepseek-chat` → `DeepSeek-V4-Flash-0731` |
| 知识库 | `f2e4c9b7-7966-42a9-b786-a3df16e7db66`：4 份冻结语料，`body-v1`，发布 revision 23 个 chunk |
| 会话 | `2f102cfc-ddc5-4241-b543-3c107dc4f74f` |

## 2. 结果（逐项）

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 引用点击 → 按行定位 | **通过** | 点“打开来源 1”打开 `01_exobrain_overview.md`，抽屉显示“引用位置：第 7–9 行”“已按来源哈希校验”；“在源码中查看”后高亮块的正文字符数与语料第 7–9 行**完全一致（437 字符）**；来源 2 打开 `03_java_backend_faq.md` 的“第 7–9 行”，两个文档各自独立定位 |
| 执行时间线 | **通过** | 展开后 9 条事件：`#1 模型调用 2.7s`、`#2 消息落库 8ms`、`#3 工具调用 KnowledgeTool 2.0s`、`#4 知识库检索 OK + retrievalId`、`#5 消息落库`、`#6 模型调用 2.2s`、`#7 消息落库`、`#8 工具调用 directAnswer`、`#9 消息落库`；全部“成功”，并显示用量 4266 / 434（共 4700）tokens |
| SSE 断线后刷新 | **通过** | 发送提问后 4 秒刷新页面（主动断开 SSE），服务端继续执行；刷新后同一会话共 10 条消息、`exobrain_run` 2 条 `SUCCEEDED`、18 条事件；界面只有 1 条提问 + 1 条回答，无“生成中”残留、无重复消息 |
| 390px 窄屏 | **不通过（发现缺陷）** | 见第 3 节 |

发送提问时页面文本也确认了引用区块：`来源（已由服务端校验，点击查看原文）`，列表项显示 `[1] 01_exobrain_overview.md` +
标题路径 `ExoBrain 项目导览：公开演示资料 › ExoBrain 技术栈：…`，与语料的 `headingPath` 一致。

## 3. 390px 窄屏缺陷（未修复，先记录）

方法：浏览器的 CDP 视口模拟（`Emulation.setDeviceMetricsOverride`、`Page.setDeviceMetricsOverride`）在本工具里被拒绝，
改用**同源 iframe 视口**（`390 × 844`，媒体查询按 iframe 视口生效）验证；这不是真机，但布局与媒体查询行为一致。

实测：

- `iframe.contentWindow.innerWidth = 390`，`document.documentElement.scrollWidth = 390`（页面级没有横向滚动）；
- 但消息列表 `div.min-h-0.flex-1.overflow-y-auto.px-5` 的 `scrollWidth = 623` / `clientWidth = 375`，即**消息列内部出现横向滚动**；
- 定位：最后一条助手消息的 `.ant-bubble-body` 被撑到 **603px**；其中引用卡片文件名 span（`min-w-0 max-w-full flex-1 truncate`）**510px**、
  标题路径 span **537px**、正文里的长链接 `<a>` **428px**。

原因：引用卡片的 flex 行没有宽度约束（父级 `flex flex-wrap` 缺 `min-w-0`/`w-full`），`truncate` 在没有确定宽度的容器里不起作用，
于是整条助手气泡按 min-content 宽度排版。

建议（**本轮未改**）：给引用卡片根容器与行加 `min-w-0 w-full`，文件名/标题 span 保持 `truncate`，长 URL 用 `break-all`。
之所以不在本轮动：该文件（`ui/src/components/views/agentChatView/AgentChatHistory.tsx`）正好有未提交的主题改动，
现在改会与那份工作冲突，需要用户确认后再修。

## 4. 未执行 / 限制

- 真机或浏览器 DevTools 设备模拟：**未执行**（工具禁用 `Emulation` 域），390px 用同源 iframe 代替。
- Safari / Firefox、移动端软键盘、无障碍读屏：**未执行**。
- 生产环境浏览器验收：**未执行**（本轮在本地隔离环境完成；生产已有 2026-10-18 的端到端脚本结果）。
- 移动端浏览器缩放、深色模式：**未执行**。

## 5. 复现要点

1. 一次性库（`exobrain_eval02`）+ 产品上传路径建 body-v1 索引（见 `deliveries/02.md` 第 12.1 节）。
2. 后端以外部配置启动，`app.auth.public-origin` 必须等于前端源，否则登录被同源校验拒绝（403，实测踩到）。
3. 前端用 `git archive HEAD ui` 导出后 `npx vite --port 5199 --host 127.0.0.1`；`vite.config.ts` 已把 `/api`、`/sse` 代理到 `8080`。
4. 发送提问用受控输入的原生 setter + `input` 事件（React 受控组件），再点 `button.ant-sender-actions-btn`。
