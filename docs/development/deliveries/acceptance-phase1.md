# 第一阶段集成验收记录（真实运行）

- 被测提交：`a6921dc`（01 `0aa79ed`、02 `a8fed26`、08 `ecdb738`、03 `6aac8cd`、04 `a6921dc` 全部合入）
- 被测构建：`git archive a6921dc` 导出到仓库外临时目录后 `.\mvnw.cmd -o package -DskipTests`，产物 `jchatmind-0.0.1-SNAPSHOT.jar`（62,769,558 字节）。**测试针对该提交构建的 jar，不是共享工作目录里后续进行中的改动。**
- 执行：主 Agent
- 结果：**账号/管理员 38 项全部通过；游客 18 项全部通过；应用日志 0 条 ERROR**

## 1. 环境

| 项目 | 实际值 |
| --- | --- |
| JDK | Temurin/OpenJDK 21.0.1（`D:\Soft\Java\jdk-21`） |
| 数据库 | Docker `pgvector/pgvector:pg17`，PostgreSQL 17.11，pgvector 0.8.7，库 `exobrain_accept`（每次运行前重建） |
| 建库方式 | `db/jchatmind.sql` + `deploy/migrations/20261014-accounts.sql` + `20261014-models.sql` + `20261016-storage-cleanup.sql` + `20261017-03-body-index.sql` |
| Embedding | 真实端点 `https://emrank.tech/v1`，模型 `BAAI/bge-m3`，1024 维（由模型目录 bootstrap 从配置导入为 `legacy-embedding`，`default_model=true`） |
| 对话模型 | 真实端点 `https://metapi.lilililwan.xyz/v1`，模型 `DeepSeek-V4-Flash-0731` |
| 存储 | 仓库外临时目录（`document.storage.base-path`） |
| 其他 | `app.auth.secure-cookie=false`（本地 HTTP）、独立 `app.models.encryption-key-file` 与 bootstrap 管理员口令文件 |

以上密钥只以环境变量/仓库外文件形式存在，未写入仓库。

## 2. 账号与管理员矩阵（`scripts/acceptance/account-and-admin.ps1`）

真实结果摘要（`PASS` 38 / `FAIL` 0）：

- 管理员登录返回 CSRF；模型目录中存在默认 Embedding 模型 `BAAI/bge-m3 / https://emrank.tech/v1 / 1024 dims` 与启用中的对话模型。
- 两个普通账号（`accept-u1`、`accept-u2`）各自创建私有知识库、上传各自的合成文档并创建只绑定自己知识库的助手。
- **真实正文索引发布**：`exobrain_kb_index_state.active_index_version = body-v1`，`exobrain_index_revision` 中 `body-v1 / PUBLISHED / document_count=1 / chunk_count=6`（`chunk_count` 为真实切块数，非估算）。
- 隔离矩阵（全部返回真实 404，不是前端过滤）：
  - u1 只能列出自己的知识库；
  - u1 读取 u2 文档原文 → `404 文档不存在`；
  - u1 删除 u2 知识库 → `404 资源不存在`；
  - 管理员列出：私有知识库不可见；
  - 管理员读取他人私有文档 → `404 文档不存在`。
- 正文事实检索与引用：提问“ALPHA 文档正文里给出的就绪探针间隔是多少秒？重试上限是几次？”（该事实只出现在正文，标题不含）
  - 答复：`根据 ALPHA 知识库（来源：alpha.md「ALPHA 运行参数」一节），ALPHA 文档正文给出的就绪探针间隔为 37 秒，重试上限为 4 次。`
  - 结构化引用 1 条：`documentId` 与上传文档一致、`startLine/endLine = 48-73`、`indexVersion = body-v1`；
  - **复核来源范围**：再走一次授权原文接口取回 Markdown，按 48–73 行切片，切片中确实包含“37 秒”（引用位置可核验，不是摆设）。
- 资料不足：提问 2026 年商业客户数量与检索准确率，答复明确“多次检索均未找到……均与您询问的两个指标无关”，未编造数字。
- 删除：u1 删除自己的知识库后，历史对话仍可读、原文变为 `404 文档不存在`、该库文档列表为空。

## 3. 游客路径（`scripts/acceptance/guest.ps1`）

真实结果摘要（`PASS` 18 / `FAIL` 0，分 setup 4 项 + guest 14 项）：

- setup（管理员）：创建公开知识库、上传并索引合成文档、创建演示助手。
- 游客（只有访客 Cookie，无会话）：
  - 只看到配置的 1 个演示助手、只看到白名单知识库与其文档；
  - 可读取已发布 Markdown 原文；
  - 非发布文档 → `404 文档不存在`；
  - 上传 → `403 公开体验不提供此管理操作`；删除知识库 → 同样 403；
  - 提问正文事实“PUBLIC 演示库的正文里写的预检超时是多少分钟？并发上限是多少？”：
    - 答复：`根据 PUBLIC 演示库（public-fact.md）正文记载：预检超时是 23 分钟，并发上限是 6 个。`
    - 引用 1 条：`documentId` 正确、`indexVersion = body-v1`、`startLine-endLine = 43-64`。

## 4. 与第一阶段验收清单的对应

| 验收项（开发指南 §6） | 结论 |
| --- | --- |
| 1. 两个普通账号分别导入不同文档、并发无串文、管理员也不能访问他人私有资料 | **本次运行通过**（隔离矩阵 5 项 + 上传/索引真实成功；并发无串文由任务 01 的 16×5 并发回归覆盖） |
| 2. 提问正文事实命中正确片段、引用能打开并定位原文；无匹配/失败/已删除来源有不同反馈 | **本次运行通过**（正文事实 + 行号复核 + 删除后 404 + 资料不足场景） |
| 4. 游客与登录用户都能真实问答、刷新后仍能看到有权访问的历史与来源 | 问答与引用**本次运行通过**；刷新/浏览器界面行为**未执行**（见第 5 节） |
| 5. lint、类型检查、构建、后端测试、真实 PostgreSQL、双模式浏览器检查 | 前五项**已执行并通过**（见下表）；双模式真实浏览器检查**未执行** |

自动化检查（同一提交，主 Agent 复跑）：

| 检查 | 命令 | 真实结果 |
| --- | --- | --- |
| 后端默认 profile | `jchatmind> .\mvnw.cmd -o test` | `Tests run: 230, Failures: 0, Errors: 0, Skipped: 4`（4 个为既有 Windows 符号链接跳过） |
| 真实 PostgreSQL 集成 | `.\mvnw.cmd -o test -Ppostgres-it`（`EXOBRAIN_IT_*` → 本机 pgvector 容器） | `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`（隔离数据库创建后删除，应用 5 个脚本） |
| 评测脚手架 | `python evaluation/scripts/validate.py --dataset ... --freeze ...`、`python -m pytest evaluation/tests -q` | 退出码 0（50 题/40 可答/10 资料不足）；`60 passed` |
| 前端 lint 与双构建 | `npm run lint`；游客版与账号版 build | 均通过（bundle 1373.55 kB / 1418.10 kB） |

## 5. 未执行与已知限制

- **真实浏览器界面检查未执行**：引用点击定位、迟到响应、390px 布局、SSE 断线重连后刷新恢复等界面行为只在代码与构建层面验证，没有跑真实浏览器。不得据此声称界面已验证。
- **02 的候选（body-v1）同条件对比未运行**：`evaluation/scripts/run.py` 的 `body-v1` 适配器尚未接入产品侧的正文索引构建（需要把冻结语料灌进 `retrieval.RetrievalIndexService` 后再调用检索）。目前已交付的是真实旧策略基线；候选对比的复现路径：用真实 Embedding 在隔离库中对 `evaluation/corpus/v1-2026-10-14` 建立 `body-v1` 索引 → 复用 `evaluation/datasets/questions-v1.jsonl` → `compare.py`。未运行前不给出任何“提升比例”。
- **检索拒绝阈值未校准**：`app.retrieval.max-distance` 默认 `null`（不启用）。本次“资料不足”场景由模型在拿到低相关片段后自行说明，不是阈值拒绝；不要把这当成拒绝率指标。
- **既有知识库仍是标题索引**：本次新建库为 `body-v1`；已经在生产的老库需要显式重建才会切换，重建前后行为不同，README 与部署说明必须区分。
- 观察到一处既有健壮性问题（未修改，属基线行为，不在本批范围）：`POST /api/agents` 传 `allowedTools: null` 会抛 `IllegalArgumentException` 并返回 500，而不是 400。普通账号正确用法是传空数组。
