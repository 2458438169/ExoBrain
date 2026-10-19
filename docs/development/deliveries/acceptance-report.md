# ExoBrain 优化验收汇报

- 汇报日期：2026-10-18（Asia/Shanghai）
- 交付负责人：主 Agent（子 Agent 承担实现，主 Agent 负责拆分、审查、集成、验收与发布）
- 依据文档：`docs/development/orchestrator.md`、`README.md`、`contracts.md`、`tasks/01–08`
- 代码基线 → 交付：`68f71d3` → `85e68b1`（`exobrain/main`，快进，无强推）
- 生产版本：`exobrain:20261018-durable-runs`（已部署并验证）

## 1. 交付范围

| 任务 | 交付内容 | 状态 |
| --- | --- | --- |
| 01 Markdown 并发隔离 | 消除单例请求正文/共享 Parser；16 份×5 轮并发回归 + 上传路径双文档检查 | 验收通过 |
| 02 检索评测基线 | 冻结语料（含 SHA-256）、50 题题集（40 可答/10 资料不足，dev30/test20）、validate/run/compare 脚本、60 项离线测试、**真实旧策略基线** | 验收通过（候选对比未跑） |
| 03 正文索引与结构化检索 | 标准化 + 正文切块 + 源哈希/行号、索引版本与原子发布、`RetrievalEnvelope`（OK/NO_MATCH/UNAVAILABLE）、迁移 03 | 验收通过 |
| 04 可核验引用 | 运行内来源登记、服务端校验 `citationIds`、消息 metadata、界面来源列表与按行定位、迁移/ADR | 验收通过（浏览器交互未测） |
| 05 执行追踪 | 运行摘要 + 顺序事件、真实耗时/重试/用量、受权读取 API、聊天页时间线、迁移 05 | 验收通过 |
| 06 持久化文档导入 | 消息+任务同事务、租约/fencing、有界重试、重启恢复、删除竞态、状态/重试 API 与界面、迁移 06 | 验收通过 |
| 07 持久化对话任务 | 提交即入队（幂等键）、领取/续租/fencing、中断语义、控制路径、迁移 07、**13 项真实 PostgreSQL 队列测试** | 验收通过（多实例/真实重启演练未做） |
| 08 CI 与工程文档 | CI 工作流、统一检查入口 `scripts/check.sh|ps1`、架构/来源/ADR/README、真实 PostgreSQL 集成测试 | 验收通过（CI 未在 GitHub 实际执行） |

## 2. 自动化验证（同一交付提交）

| 检查 | 命令 | 真实结果 |
| --- | --- | --- |
| 后端单元（默认 profile） | `jchatmind> .\mvnw.cmd -o test` | **269 通过 / 0 失败 / 4 跳过**（跳过均为既有 Windows 符号链接限制） |
| 真实 PostgreSQL 集成 | `.\mvnw.cmd -o test -Ppostgres-it` | **51/51 通过**：基线 6、正文索引 13、执行追踪 5、持久导入 14、对话队列 13 |
| 评测脚手架 | `validate.py --freeze` + `pytest evaluation/tests` | 退出码 0（50 题、split/哈希/行号一致）；**60 passed** |
| 前端 | `npm run lint` + 游客版/账号版两套 build | 通过（lint 0 error） |
| 网关配置 | `nginx -t`（容器内与生产） | syntax is ok / test is successful |
| CI 工作流 | `.github/workflows/ci.yml` | **未在 GitHub 实际执行**（本机无运行器） |

## 3. 真实端到端验收（独立构建的 jar，真实模型）

被测方式：`git archive <commit>` 导出后在仓库外构建，本地起服务，真实 Embedding（`BAAI/bge-m3`，1024 维）+ 真实对话模型，PostgreSQL 17 + pgvector。

| 轮次 | 被测提交 | 结果 |
| --- | --- | --- |
| 第一阶段（01–04+08） | `a6921dc` | 账号/管理员 **38/38**、游客 **18/18** |
| 加入 05/06 | `dd19818` | 账号/管理员 **40/40**、游客 **5/5 + 14/14**，应用日志 0 ERROR |
| 加入 07（队列） | 同脚本再跑 | **40/40 + 19/19**，队列与运行记录均 `SUCCEEDED` |

关键行为证据：

- **权限隔离**：u1 不能列出/读取/删除 u2 的知识库与文档（真实 404）；管理员同样读不到他人私有文档；游客只能看白名单库与已发布文档，上传/删除返回 403。
- **正文索引**：上传返回 `documentId + jobId + indexStatus=QUEUED`，worker 完成后 `SUCCEEDED`（`chunksDone/chunksTotal=6/6`）；`exobrain_kb_index_state.active_index_version = body-v1`。
- **可核验引用**：提问“正文里的就绪探针间隔”得到 `37 秒 / 上限 4 次`，引用 `indexVersion=body-v1`、行号 `48-73`；再用授权原文接口取回 Markdown 按该行区间切片，**切片确实包含该事实**。游客侧同样得到 `23 分钟 / 并发 6` 与行号 `43-64`。
- **资料不足**：问商业客户数与准确率，答复明确“未找到相关记录”，未编造数字。
- **删除语义**：删库后历史对话仍可读、原文 404、文档列表为空。
- **执行记录**：3 次真实问答在 `exobrain_run` 留下 `SUCCEEDED` 摘要与 35 条顺序事件，答复 metadata 带服务端 `runId`。

## 4. 评测证据（任务 02）

- 真实旧策略基线（标题向量 + L2 + top3，真实 Embedding + 真实 pgvector）：Hit@3 **0.850**、Recall@3 **0.812**、MRR@3 **0.738**；无答案 10 题检索级拒绝率 **0.000**（标题索引不会拒绝）；检索延迟 p50 5467 ms / p95 5891 ms（由远端 Embedding 主导）。
- 分节数交叉校验：三份公开资料 = 6+6+6 = **18** section，与公开基线一致。
- **未交付**：body-v1 候选的同条件对比（需把冻结语料灌进产品侧索引构建再评分）。复现路径写在验收记录第 5 节；未跑就不给“提升比例”。

## 5. 生产发布（已执行）

- 备份：`backups/pre-durable-runs-20261018-134926/`（db.sql.gz / documents.tar.gz / secrets / 旧 release / nginx.conf / compose.yaml）。
- 迁移：03 → 05 → 06 → 07 顺序应用 + 应用角色 GRANT（新增 6 张表），原有数据 **2 KB / 3 文档 / 18 chunk / 10 会话 / 62 消息 / 2 账号** 行数不变。
- 镜像与前端的：`exobrain:20261018-durable-runs`；`releases/20261018-durable-runs/{ui,admin}`。
- 网关：demo 白名单加入 `chat-sessions/<uuid>/runs`、`runs/<uuid>`、`documents/<uuid>/ingestion`；`nginx -t` 通过后 reload。
- 线上验证：容器 healthy、无 ERROR 日志；首页 200、`/admin/` 200、游客 agents/knowledge-bases 200、公开文档预览 200（2615 字符）；未知 demo 路径 404（fail-closed 保留）；新账号路由无会话 401。
- 回滚：切回 `releases/20261016-kb-privacy` + 旧镜像即可；迁移为增量，旧代码可读旧列，**不要回删表**。

## 6. 仍未解决的限制（不得据此宣称已完成）

1. ~~**02 的候选同条件对比未运行**~~ **已补做（2026-10-18）**：用产品上传路径在一次性库 `exobrain_eval02` 建 body-v1 索引（23 chunk / 4 文档），同一题集跑 50 题。冻结判据下 Hit@3 **0.600**（基线 0.850）、Recall@3 0.588、MRR@3 0.562；归因见 `deliveries/02.md` 第 12 节：17/44 个证据单元只差「标题行」边界（诊断口径 43/44 单元、40/40 题覆盖），**判据与题集未修改**。对外报数必须同时写明这条归因。
2. ~~**真实浏览器界面检查未执行**~~ **已补做（2026-10-18）**：引用点击定位到 `01_exobrain_overview.md` 第 7–9 行，高亮文本与语料逐字符一致（437 字符）；执行时间线 9 条事件带耗时与 token 用量；发问后 4 秒刷新（断 SSE）服务端仍完成，刷新后只有 1 问 1 答；**390px 发现真实缺陷**（引用卡片无宽度约束，助手气泡按 603px min-content 排版，消息列出现横向滚动），未改代码。见 `deliveries/browser-acceptance.md`。
3. ~~**07 的多实例、并发 HTTP 提交、真实 JVM 重启演练未做**~~ **已补做（2026-10-18）**：两个真实 JVM 进程接同一个库——同会话并发一个 200 一个 409（只 1 个运行）、幂等键三次返回同一 `runId`（`attempts=1`）、`kill -9` 持锁实例后 22.1 s 由存活实例标记 `INTERRUPTED_BY_RESTART` 且不重放、默认租约下 3/3 冒烟通过。另发现：租约压到 20 s（低于单次模型时延）时，**存活实例自己的运行**会被过期扫描判中断；默认 120 s 不受影响。见 `deliveries/durable-runs-drill.md`。
4. **检索拒绝阈值未校准**（`app.retrieval.max-distance` 默认关闭）；既有生产库仍是标题索引，需显式重建才切 `body-v1`。
5. **CI 未在 GitHub 实际执行**；且工作流的 `on: push` 触发方式尚未按用户意见调整（待定：仅手动 / 删除 / 仅 PR）。
6. 外部调用不提供 exactly-once；导入与对话队列重复执行可能重复计费。
7. **多主机/网络分区/时钟漂移未验证**；390px 缺陷待与未提交的 UI 主题改动对齐后再修。

## 7. 过程记录与偏差

- 子 Agent 共 11 个会话，其中 3 个因上游服务中断（403）或配额耗尽（`quota_exceeded`）退出，均由主 Agent 接手核验、补齐记录并完成验收。
- 已修复的自身缺陷：06 提交漏带一个测试文件导致提交态编译失败（`dd19818` 修复）；生产 `nginx.conf` 打补丁时一度取错行号，`nginx -t` 通过但校验行被注释覆盖，**在 reload 前发现并恢复**，随后才 reload。
- 详细逐任务证据见 `docs/development/deliveries/01.md` – `08.md` 与 `status.md` 台账。

## 8. 独立验收反馈（2026-10-19，针对 `65b1237`）与修复状态

外部独立验收对提交 `65b1237` 判定**暂不通过**，共 6 项；本仓库的处置如下（改动**未提交**）。

| 编号 | 级别 | 问题 | 状态 | 证据 |
| --- | --- | --- | --- | --- |
| F01 | P1 | 运行已 `INTERRUPTED` 后，过期 worker 仍能写入最终答复 | **已修复** | 新增 `RunResultWriter`（锁任务行 + fencing + `RUNNING` 校验，消息与答案关联同事务）+ 租约独立心跳；`RunResultGuardIntegrationTest` 5 项全过 |
| F02 | P1 | 前端未接入幂等键，网络重试会重复执行 | **已修复** | `AgentChatView` 为每个发送意图生成并复用稳定键；真实 Chromium 丢弃响应后重试：2 次提交、1 个运行、1 条用户消息、`attempts=1` |
| F03 | P2 | 同键并发重试参数顺序错误导致误报 409 | **已修复** | `RunSubmissionService.replayAfterConflict` 改为 `(actorScope, sessionId, key)`；集成测试断言同键同内容取回原 `runId`、同键异内容仍冲突 |
| F04 | P2 | 无 assistant 消息的 `CANCELLED/FAILED/INTERRUPTED` 运行刷新后不可见 | **已修复** | `AgentChatHistory` 按 `triggeringMessageId` 展示无答复运行的状态/错误码/耗时；真实取消与真实中断两个会话实测可见 |
| F05 | P2 | 冻结资产与自身哈希不一致（CRLF/LF），评测不能直接复现 | **已修复** | `.gitattributes` 为 `evaluation/corpus/**`、`evaluation/datasets/**`、`docs/public-demo/*.md` 固定 `eol=lf`；`core.autocrlf=true` 的检出/导出后 `validate.py` 退出 0、`pytest` 74 passed，冻结哈希全部匹配 |
| F06 | P2 | 390px 窄屏长引用撑破消息区域 | **已修复（待验收方复测）** | 引用卡片加 `min-w-0 max-w-full overflow-hidden`、文件名 `break-all`、标题 `break-words`、助手正文 `break-words [&_a]:break-all`；390px 与 320px 视口下消息列 `scrollWidth == clientWidth`，无元素超宽 |

### 8.1 本轮复跑

- 后端单元：`-Punit` **269 项，265 通过 / 4 跳过 / 0 失败**。
- 真实 PostgreSQL：`-Ppostgres-it` **56 项，55 通过 / 0 失败 / 1 错误**；错误项
  `RetrievalIndexIntegrationTest.realEmbeddingAnswersBodyOnlyFacts` 由外部 Embedding 端点
  `HTTP 503 completion_backlog` 引起（`curl` 直接复现），**不是代码缺陷**，端点恢复后需重跑。
- 前端：`eslint` 与 `tsc -b && vite build` 通过。
- 评测：`.gitattributes` 生效后的干净检出 `validate.py` 退出 0、`pytest evaluation/tests` 74 passed（本机此前为 67 passed/1 failed/6 error）。

### 8.2 与 F06 相关的界面改动边界

工作区里另有一份**未提交**的界面主题改动（sakura/milk/skyblue 主题，15 个文件）。本轮把 F06 的窄屏修复
叠加在该主题之上验证，两者都保持未提交；提交与发布时机由用户决定。

### 8.3 尚未完成

- F01 的**真实 HTTP 注入延迟**复现脚本本轮未重跑（只用真实数据库集成测试覆盖同一写入路径）。
- 新的真实付费模型 / Embedding 对比未执行；GitHub Actions 本轮仍未在远端执行。

## 9. 第二轮独立验收（2026-10-19，R2）与修复

第二轮独立验收确认 **F03–F06 通过、F01 的迟到写入漏洞已挡住**，但新增 1 个 P1 + 3 个 P2（均落在上一轮修复内）。
本轮已全部处理：

| 编号 | 级别 | 问题 | 修复 | 验证 |
| --- | --- | --- | --- | --- |
| R2-01 | P1 | 首条消息手动重试成功后，SSE 重连换新键重发（2 运行 / 2 次模型调用） | 任何一次确认受理都清空 `initialMessage` 并置 `initialSent=true`；失败只回退标记、保留意图 | 真实浏览器：丢响应 → 手动重试（同键）→ **杀掉后端 JVM 迫使 SSE TCP 断开并自动重连**（`已连接` 只由 `init` 设置）→ 提交次数仍 2、键仍 1 个；DB 1 运行 / 1 用户消息 |
| R2-02 | P2 | 终态重放后停在“生成中”、输入禁用 | 消费响应里的 `runStatus` / `idempotentReplay`：终态恢复输入并给提示，活动态继续等待 | 同上复现：`textarea.disabled=false`、提示“该问题此前已提交并完成，这次没有重复执行” |
| R2-03 | P2 | 取消跨过心跳周期被误记为重启中断 | `beat()`/`checkpoint()` 区分取消 / 被锁挡住 / 真丢失；过期扫描对 `cancel_requested=true` 写 `CANCELLED / CANCELLED_BY_USER` | 新增集成测试 `cancellationOutlivingTheLeaseIsNotReportedAsRestart`（真实 PG） |
| R2-04 | P2 | 一个任务的行锁堵死唯一心跳线程，连累其它运行 | 每个租约独立单线程调度器；心跳续租走独立连接 + `SET LOCAL lock_timeout`，被挡只跳过一拍 | 新增集成测试 `heartbeatIsIsolatedFromAnotherRunsRowLock`：A 的行锁被持有 6 s，B 仍正常续租并 `SUCCEEDED` |

### 9.1 本轮复跑

- 后端单元 `-Punit`：**269，265 通过 / 4 跳过 / 0 失败**。
- 真实 PostgreSQL `-Ppostgres-it`：**58 项，57 通过 / 0 失败 / 1 错误**；唯一错误仍是
  `RetrievalIndexIntegrationTest.realEmbeddingAnswersBodyOnlyFacts`，外部 Embedding 端点返回
  `HTTP 503 completion_backlog`（本轮再次 `curl` 复测仍 503），与代码无关。
- 前端 `eslint` 与 `tsc -b && vite build` 通过。
- 冻结评测：`.gitattributes` 生效后的干净检出仍为 `validate.py` 退出 0、`pytest 74 passed`（F05 未被本轮改动影响）。

### 9.2 仍未完成

- 真实付费模型 / Embedding 对比未执行（端点 503）；GitHub Actions 仍未在远端执行。
- R2-01 复现用的是「杀掉后端 JVM 让 SSE 断开」，与验收方的「重启本地代理」等价但不完全相同。

## 10. 第三轮独立验收（2026-10-19，R3）与修复

第三轮验收确认 **R2-01～R2-04 四项通过**，新增 1 个 P2，本轮已修复：

| 编号 | 级别 | 问题 | 修复 | 验证 |
| --- | --- | --- | --- | --- |
| R3-01 | P2 | `checkpoint()` 收到 `BLOCKED` 直接放行，行锁等待跨过租约期限后仍继续发起模型请求（两次复现超出期限 2.608711 s / 2.606319 s，最终无迟到答案落库） | `BLOCKED` 后先在预算内（租约 1/3，最多 5 s）重试续租；预算耗尽改用不加锁的 `findById` 确认：状态非 `RUNNING` / fencing 前进 / 租约过期 / 读不到 → `RunLeaseLostException`；`cancel_requested` → `RunCancelledException`；仅确认有效才放行这一步 | 三条真实 PostgreSQL 回归：到期→停止、无法确认→停止（且未续租）、仍有效→放行；`RunResultGuardIntegrationTest` **10/10** |

### 10.1 本轮复跑

- 后端单元 `-Punit`：**271，267 通过 / 4 跳过 / 0 失败**。
- 真实 PostgreSQL `-Ppostgres-it`：**61 项，60 通过 / 0 失败 / 1 错误**；唯一错误仍是
  `RetrievalIndexIntegrationTest.realEmbeddingAnswersBodyOnlyFacts`——外部 Embedding 端点返回
  `HTTP 503 completion_backlog`（本轮该用例实际执行，未跳过）。
- 前端本轮没有改动，`eslint` / 构建结果沿用 R2。

### 10.2 仍未完成

- 验收方 R3 使用的 HTTP + 合成模型服务故障注入脚本不在本仓库，本轮未重跑；R3-01 的结论以三条真实 PG 回归为准。
- 真实付费模型 / Embedding 效果对比仍未执行（端点 503）；GitHub Actions 远端执行仍无结论。
- 前端 20 个文件（主题，以及 R2-01/R2-02、F04、窄屏、知识库排版等修复）仍在工作区未提交（按用户要求）。
