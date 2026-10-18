# ExoBrain 优化任务台账

由主 Agent 维护。子 Agent 交付先进入“待审查”，主 Agent 核对 diff、接口与真实测试证据后才标记“验收通过”。

## 0. 工作环境与起点

| 项目 | 实际值 |
| --- | --- |
| 仓库工作目录 | `D:\Code\Java\JChatMind`（共享目录模式，仅主 Agent 执行 Git 暂存/提交/推送） |
| 分支 | `main`，起始提交 `68f71d3`（文档基线 `212a17c` 之上两笔文档提交） |
| 远程映射 | `exobrain` → `https://github.com/2458438169/ExoBrain.git`（推送目标）；`origin` → 上游 `youngyangyang04/JChatMind`（不推送） |
| 起始未提交修改 | 仅未跟踪目录 `.claude/`、`.idea/`（非本任务产物，保持不提交） |
| JDK | `D:\Soft\Java\jdk-21`（Java 21，PATH 上默认 Java 8 需先覆盖 `JAVA_HOME`） |
| 前端 | Node `v24.18.0`、npm `11.16.0`；`ui/node_modules` 已安装 |
| 评测 Python | `D:\Soft\Python\Anaconda5.3.0\python.exe`（Python 3.7.0，pytest 3.8.0，requests 可用；2026-10-18 离线用例 74 passed） |
| 真实 PostgreSQL | Docker 容器 `exobrain-dev-pg`（`pgvector/pgvector:pg17`，`127.0.0.1:15432`，用户/库 `exobrain`） |
| 真实 Embedding 服务 | `https://emrank.tech/v1/embeddings`，模型 `BAAI/bge-m3`，实际返回 1024 维（探测提交见下） |
| 生产部署 | 本机无可达的线上环境与凭据；生产发布状态按本台账末尾“发布状态”一节据实说明 |

坐标探测记录（主 Agent，2026-10-16）：

- `GET https://emrank.tech/v1/models` → HTTP 200，`BAAI/bge-m3` 在模型目录内；仓库内 `application-dev.yaml` 的 `cloudflare/bge-m3` 已失效（HTTP 404）。
- `POST https://emrank.tech/v1/embeddings`，`model=BAAI/bge-m3` → HTTP 200，`dims=1024`。

## 1. 任务台账

状态取值：待开始 / 执行中 / 待审查 / 返工 / 验收通过 / 受阻。

| 编号 | 负责人 | 工作目录 | 起始提交 | 依赖 | 允许修改文件 | 状态 | 交付提交 | 证据位置 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 01 Markdown 并发隔离 | 子 Agent A1（中断后主 Agent 核验收尾） | 共享目录 | `68f71d3` | 无 | `MarkdownParserServiceImpl.java`、新增解析器测试、`deliveries/01.md` | **验收通过** | `0aa79ed` | `docs/development/deliveries/01.md`（49 测试通过、4 个 Windows 符号链接跳过） |
| 02 检索评测基线 | 子 Agent A2 → 子 Agent A2b（上游中断后重派） | 共享目录 | `68f71d3` | 无（候选对比依赖 03） | `evaluation/**`、`deliveries/02.md` | **验收通过**（基线 + 2026-10-18 补做 body-v1 同条件对比） | `a8fed26`；对比见本批提交 | `evaluation/runs/legacy-title-v1/summary.json`（真实基线 Hit@3 0.850 / Recall@3 0.812 / MRR@3 0.738；`validate.py` 退出 0；pytest 74 passed）；`evaluation/runs/body-v1/`（真实候选 Hit@3 0.600，`comparison.md`、`chunks.csv`、`index-snapshot.json`、`boundary-diagnostic.json`） |
| 03 正文索引与结构化检索 | 子 Agent B1 + 主 Agent 审查 | 共享目录 | `0aa79ed` | 01（已合入）；与 02 共用语料 | `retrieval/**`、`DocumentFacadeServiceImpl`、chunk 实体/mapper、`RagService*`、`KnowledgeTools`、`DemoKnowledgeTool`、`20261017-03-body-index.sql`、`docs/adr/0003-*.md`、`deliveries/03.md` | **验收通过** | `6aac8cd` | `deliveries/03.md`；默认 profile 230/0/0/4、`-Ppostgres-it` 19/19（主 Agent 复跑） |
| 04 可核验引用 | 子 Agent B1（中断后主 Agent 收尾） | 共享目录 | `ecdb738` | 03 协议；前端按契约开发 | `citation/**`、`JChatMind.java`、`DirectAnswerTool`、`ChatMessageDTO`、`DemoCatalogService`、`AgentChatHistory.tsx`、`DocumentPreviewDrawer.tsx`、`ui/src/types`、`ui/src/api`、`docs/adr/0004-*.md`、`deliveries/04.md` | **验收通过**（2026-10-18 补做真实浏览器交互验收：引用按行定位、执行时间线、SSE 断线刷新通过；390px 发现布局缺陷，见 `deliveries/browser-acceptance.md`） | 见下一提交 | `deliveries/04.md`（230 单测 + 19 集成 + lint + 两套构建）；`deliveries/browser-acceptance.md` |
| 05 执行追踪 | 子 Agent C1 | 共享目录 | `a6921dc` | 04（已合入） | `execution/**`、run/event mapper/DTO/service、`20261017-05-runs.sql`、`docs/adr/0005-*.md`、`deliveries/05.md` | **验收通过** | `39108e4` | `deliveries/05.md`；单元 265（当时）、集成 38/38、端到端含 `runId` 与事件 |
| 06 持久化文档导入 | 子 Agent C2 | 共享目录 | `a6921dc` | 03（已合入） | `ingestion/**`、`DocumentFacadeServiceImpl`、`20261017-06-ingestion.sql`、导入状态 UI、`deliveries/06.md` | **验收通过** | `09814f3` | `deliveries/06.md`；单元 265、集成 38/38（含 14 项故障注入）、端到端上传返回 jobId 后 `SUCCEEDED` |
| 07 持久化对话任务 | 子 Agent（上游配额耗尽中断）→ 主 Agent 收尾 | 共享目录 | `39108e4` | 05（已合入）；参考 06 | `runs/**`、`20261017-07-durable-runs.sql`、提交/入队路径、`CreateChatMessageRequest/Response`、`DemoChatService`/`DemoController`、`docs/adr/0007-*.md`、`deliveries/07.md` | **验收通过**：单元 269、集成 51/51（含 13 项队列故障/重启场景）、端到端 40/40 + 19/19；仍未验证多实例、浏览器与并发 HTTP 提交（见 `deliveries/07.md` 第 6 节） | `1f4d776c` 之后本批提交 | `docs/development/deliveries/07.md` |
| 08 CI、工程文档与集成 | 子 Agent A3（中断）→ 子 Agent A3b | 共享目录 | `68f71d3` | 基础部分独立；验收随批 | `.github/**`、`pom.xml`、`scripts/**`、`README.md`、`docs/architecture.md`、`docs/adr/**`、`docs/provenance.md`、`deliveries/08.md` | **验收通过**（文档中的“待实现”标注在 03 合入后由主 Agent 修正） | `ecdb738` | `deliveries/08.md`；`-Punit` 165 / `-Ppostgres-it` 6（隔离副本），复跑后 230 / 19 |

共享文件所有权（本轮实际约定）：

- `jchatmind/pom.xml`、`ui/package.json`、锁文件：仅 08 修改，其他任务通过主 Agent 申请小补丁。
- 根 `README.md`、`deploy/README.md`、`deploy/nginx.conf`：08 起草，主 Agent 最终定稿。
- `docs/development/deliveries/status.md`：仅主 Agent 维护。
- `MarkdownParserService*`：01 → 03 串行接续。
- `DocumentFacadeServiceImpl`、chunk 实体/mapper：03 → 06 串行接续。
- `JChatMind.java`、消息 metadata：04 → 05 → 07 串行接续。
- 新增数据库迁移：按任务编号各自负责，执行顺序由主 Agent 在 08 文档中固化。

## 2. 波次与调度记录

| 波次 | 分派 | 实际并发 | 备注 |
| --- | --- | --- | --- |
| A | 01、02、08 基础并行；主 Agent 核实基线、准备真实 PostgreSQL 与 Embedding 坐标 | 子 Agent 3 + 主 Agent | 共享目录，文件所有权已在上表固定 |
| B | 03 正文索引（依赖已合入的 01）、08 剩余部分（CI + 工程文档）；02 收尾（数据集/测试/真实基线） | 子 Agent 3 + 主 Agent | 2026-10-17 18:20 前后分派 |
| C | 04 可核验引用（消费 03 的契约） | 子 Agent + 主 Agent | 03 冻结协议后分派；04 会话中断后由主 Agent 补齐 ADR、交付记录与实测 |
| D | 05 执行追踪 + 06 持久化导入并行；第一阶段端到端验收（独立构建） | 子 Agent 2 + 主 Agent | 第一阶段是检查点：03/04 合入并提交后用 `git archive` 独立构建跑真实验收 |
| E | 07 持久化对话任务（依赖 05，参考 06 的租约模式） | 子 Agent + 主 Agent | 07 子 Agent 因上游配额耗尽中断，主 Agent 接手核验正常路径、补 ADR/交付记录 |
| F | 统一回归（单元/集成/前端/端到端）、README 与部署文档校正、推送 `exobrain` | 主 Agent | 见第 3、4 节；生产部署未执行 |

> 说明：波次 A 的三个子 Agent 均被上游模型服务中断（403），其中 02 由新会话接手收尾；01 与 08 的已完成代码由主 Agent 亲自复核（`.\mvnw.cmd -o test "-Dtest=MarkdownParser*Test,Document*Test,RagServiceImplTest"` → 49 通过 / 4 跳过；`.\mvnw.cmd -o test -Ppostgres-it` 对真实 PostgreSQL 17.11 → 6 通过 / 0 跳过），01 已提交为 `0aa79ed`。

## 3. 发布状态

- 代码推送：**已完成**。`exobrain`（https://github.com/2458438169/ExoBrain.git）的 `main` 从 `68f71d3` 快进到 `85e68b1`（本轮 16 个提交：01–07、08、验收材料与 07 的队列测试）。未推送上游 `origin`，未强推，未覆盖他人提交；推送需经本机系统代理（`127.0.0.1:7897`）访问 github.com。
- 生产部署：**已执行（2026-10-18，Asia/Shanghai）**。
  - 镜像 `exobrain:20261018-durable-runs`（JAR 62,928,190 字节，`FROM exobrain:20261014` + 覆盖 app.jar），容器 healthy。
  - 迁移按 03 → 05 → 06 → 07 顺序应用并补齐应用角色 GRANT（新增 6 张表：`exobrain_index_revision`、`exobrain_kb_index_state`、`exobrain_run`、`exobrain_run_event`、`exobrain_ingestion_job`、`exobrain_chat_run_job`）。
  - 前端切换到 `releases/20261018-durable-runs`（`ui/` 游客版 + `admin/` 账号版），`current` 符号链接已切换。
  - 网关：生产 `nginx.conf` 的 demo 白名单加入 `chat-sessions/<uuid>/runs`、`runs/<uuid>`、`documents/<uuid>/ingestion`，`nginx -t` 通过后 `systemctl reload nginx`。
  - 备份：`backups/pre-durable-runs-20261018-134926/`（db.sql.gz、documents.tar.gz、secrets、旧 release、nginx.conf、compose.yaml）。
  - 验证：应用与数据库 healthy、启动后无 ERROR 日志；首页 200、`/admin/` 200、游客 agents/knowledge-bases 200、公开文档预览 200（`03_java_backend_faq.md`，2615 字符）、未知 demo 路径 404（fail-closed 仍在）、`/api/runs/*` 与 `/api/chat-sessions/*/runs` 无会话 401；数据行数保持 2 KB / 3 文档 / 18 chunk / 10 会话 / 62 消息 / 2 账号不变。
  - 回滚：`ln -sfn releases/20261016-kb-privacy current` 并把 compose 的 image 改回 `exobrain:20261016-kb-privacy` 后 `docker compose up -d app`；迁移为增量、旧代码可读旧列，不要回删表。<br>说明：本次发布在 07 的队列测试补齐之后执行；`docs/adr/0007` 与 `deliveries/07.md` 记录的未验证项（多实例、真实 JVM 重启演练、浏览器交互）仍然有效。

## 4. 第一阶段验收（已完成）

- 记录：`docs/development/deliveries/acceptance-phase1.md`（含阶段一与 05/06/07 之后的两次真实复跑）
- 被测提交：第一阶段 `a6921dc`（38/38 + 18/18）；加入 05/06 后 `dd19818`（40/40 + 19/19）；加入 07 后同一脚本再次复跑（40/40 + 19/19，队列与运行记录均为 `SUCCEEDED`）。每次都用 `git archive` 导出后独立构建的 jar 运行，不是共享工作目录里的进行中改动。
- 真实环境：PostgreSQL 17.11 + pgvector 0.8.7、真实 Embedding `BAAI/bge-m3`（1024 维）、真实对话模型（DeepSeek-V4-Flash-0731）。
- 结果：账号/管理员矩阵 40/40、游客路径 19/19，应用日志 0 条 ERROR；正文事实命中并给出可复核的行号引用（48-73 / 43-64，`indexVersion = body-v1`）；上传返回 jobId 后任务 `SUCCEEDED`（6/6 chunks）；`exobrain_chat_run_job` 与 `exobrain_run` 均有 `SUCCEEDED` 记录。
- 同一提交的自动化检查：后端默认 profile 269 通过 / 4 跳过、真实 PostgreSQL 集成 51/51（含 07 的 13 项队列场景）、评测 `validate.py` 退出 0 与 `pytest 60 passed`、前端 lint 与两套构建通过。
- 未执行：真实浏览器界面检查；07 的多实例、并发 HTTP 提交与真实 JVM 重启演练（队列语义本身已由 13 项集成测试覆盖，见 `deliveries/07.md` 第 6 节）。
- 2026-10-18 补做：02 的 body-v1 同条件对比（隔离库 `exobrain_eval02`，产品切块 + 真实 Embedding；见 `deliveries/02.md` 第 12 节）。冻结判据下 Hit@3 0.600 < 基线 0.850，但 17/44 个证据单元只差「标题行」边界（诊断口径 43/44 单元、40/40 题），判据与题集均未修改。
