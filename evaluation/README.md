# ExoBrain 检索评测（开发任务 02）

本目录是可复现的检索评测基线：冻结语料、题集与标签、离线校验器、离线测试、真实检索运行器与对比脚本。
它只测**检索**（Embedding + 向量查询），不调用生成模型，因此不产出回答质量或事实支持度结论。

- 任务说明：[docs/development/tasks/02-retrieval-evaluation.md](../docs/development/tasks/02-retrieval-evaluation.md)
- 共享契约（来源哈希/行号/indexVersion 语义）：[docs/development/contracts.md](../docs/development/contracts.md) 第 2、3 节
- 交付报告：[docs/development/deliveries/02.md](../docs/development/deliveries/02.md)

## 1. 目录与职责

| 路径 | 内容 |
| --- | --- |
| `corpus/v1-2026-10-14/manifest.json` | 冻结语料清单：只有 `documents` 列出的原始文档进入索引 |
| `corpus/v1-2026-10-14/docs/*.md` | 三份公开演示资料（与 `docs/public-demo/` 逐字节相同） |
| `corpus/v1-2026-10-14/synthetic/eval-only-java-notes.md` | 评测专用合成文档（`synthetic-eval-only`，不得上传产品知识库） |
| `datasets/questions-v1.jsonl` | 50 题（40 有答案 / 10 资料不足），含 gold 证据单元与期望事实 |
| `datasets/freeze.json` | 冻结记录：题集与保留测试集哈希（调参前固定） |
| `scripts/exo_common.py` | 路径、哈希、JSON(L)、分位数等工具 |
| `scripts/exo_corpus.py` | manifest 校验、语料快照、分节规则 |
| `scripts/exo_dataset.py` | 题集结构/证据/split 校验 |
| `scripts/exo_scoring.py` | 命中判据与指标（纯函数，离线可测） |
| `scripts/exo_runtime.py` | Embedding HTTP 客户端、psql 桥、检索策略适配器 |
| `scripts/validate.py` | 命令行校验器（题集 + 语料 + 冻结哈希） |
| `scripts/run.py` | 真实运行器：建索引、逐题检索、输出结果与 run manifest |
| `scripts/compare.py` | 同条件两次运行的对比报告 |
| `tests/` | 离线 pytest（失败路径与已知排名夹具），不访问网络与数据库 |
| `runs/legacy-title-v1/` | 真实基线结果（逐题 JSONL、失败、请求、SQL、run manifest） |

## 2. 语料与来源许可

- 三份公开演示资料是本仓库自写的项目说明（仓库根 `LICENSE`：MIT），核对日期 2026-10-14；语料副本与 `docs/public-demo/01..03*.md` **逐字节相同**（`tests/test_frozen_assets.py` 会校验）。`manifest.documents[].sourcePath` 记录来源路径，`sha256`/`normalizedSha256` 记录原始字节与标准化文本（UTF-8 解码 → 去首个 BOM → CRLF/CR 统一为 LF）的哈希。
- `synthetic/eval-only-java-notes.md` 是本任务自写的合成文本，`role = synthetic-eval-only`。它**刻意写入与仓库事实不符的数字**（Java 17、Spring Boot 3.2.0、React 16、512 维、返回 10 个片段、MySQL 全文检索），用于制造「同标题不同内容」与「相近但错误干扰信息」。任何引用它的回答都应判为错误；它不得上传到产品知识库、公开演示环境或产品文档。
- **不入库**：题集/答案标注/冻结记录（`datasets/**`）、评测脚本与测试、`docs/development/**`、`docs/public-demo/99_test_questions.md`（只作为出题种子）与 `agent-system-prompt.md`。索引只从 `manifest.documents` 取文档，`run.py` 不会扫描目录。
- 语料不含私人数据、账号信息、Cookie 或线上配置；密钥从不进入语料、题集、结果或仓库内任何文件。
- 评测在**隔离目标库**中进行：容器 `exobrain-dev-pg` 里的 `exobrain_eval`，只操作本次运行自己的 `kb_id`（`DELETE FROM chunk_bge_m3 WHERE kb_id = <eval kb uuid>`），不触碰 `exobrain`（应用库）。

## 3. 命中判据（固定口径）

一条 hit（检索片段）**覆盖**某个 gold 证据单元 `E`，当且仅当：

1. `hit.docKey == E.docKey`，且两侧 `sourceHash` 都存在时相等（任一侧缺失则跳过哈希比对，只能靠行号与事实核验）；
2. hit 的来源行范围**完全包含** `E` 的行范围（`hit.startLine <= E.startLine` 且 `hit.endLine >= E.endLine`）——只与某一行相交**不算**命中；
3. `E.facts` 中每条事实字符串（空白折叠后）都出现在 hit 返回给模型的**正文**（`payload`）里；
4. hit 缺少 `startLine/endLine` 或缺少 payload 时视为「无法核验」，不计命中。

证据单元角色：`required`（参与 Hit/Recall/MRR）、`distractor`（相近但错误的干扰，单独统计）、`insufficiency`（资料不足题里说明边界的语句，不参与命中率）。

题集里的证据用 `docKey + sourceHash + startLine/endLine + facts` 标注（**不使用会随重新分块变化的 chunk UUID**）；同一个语义问题组内的多个问题共用同一证据签名；不同 `groupId` 不允许出现完全相同的必填证据签名（防止同义改写被拆到不同组/不同 split）。

## 4. 指标口径

| 指标 | 分母/算法 | 说明 |
| --- | --- | --- |
| Hit@k | 前 k 含**至少一个**被覆盖 required 证据单元的有答案题数 / 有答案题数 | 覆盖判据见第 3 节，不只按文档名 |
| Recall@k | 每题被覆盖的 required 证据单元数 / 该题 required 证据单元总数，再按题平均 | 重复片段覆盖同一证据只算一次 |
| MRR@k | 每题第一个正确证据排名的倒数（无命中记 0），再平均 | 只在有答案题统计 |
| 微平均 Recall@k | 所有题的 (被覆盖证据数 / gold 证据总数) 汇总比 | 与宏平均并列，避免小题数类别放大 |
| 跨文档覆盖率@k | 每题覆盖的**文档**数 / 该题 required 涉及的文档数，按题平均 | `allRequiredDocumentsCoveredRateAt` 为全文档覆盖的题目比例 |
| 无答案拒绝表现 | 无答案题中检索返回 `NO_MATCH` 的比例 | 检索级拒绝；**服务故障不计入拒绝**，单独计数并给出排除故障后的比例 |
| 干扰片段命中率 | 有 distractor 单元的题中，干扰单元被 top-k 返回的比例 | 衡量「相近但错误」资料的干扰程度 |
| 延迟 | 检索（含 Embedding）与每题 Embedding 分别报 `n / min / p50 / p95 / max / mean` | 顺序执行、无缓存、已预热一次；样本量小时 p95 只是插值点，见第 8 节 |

排期与 k：`kValues = [1, 3]`，`topK = 3`；`compare.py` 默认按 k=3 报告。回答级指标（引用有效性、事实支持度）不在本任务范围。

## 5. 语料、题集与 split 冻结

- `datasets/questions-v1.jsonl`：50 题 = 40 有答案 + 10 资料不足；按**语义问题组**切分为 dev 30 题（60%）/ test 20 题（40%）。同一 `groupId` 只出现在一个 split 里，同义改写（`paraphrase-zh`）与政策问题（`same-title-different-content`、`distractor`）与其原始问题同组，因此**不会一份在调参集、一份在测试集**。
- 类别分布：`body-fact` 18、`title-fact` 10、`insufficient-info` 10、`paraphrase-zh` 5、`cross-document` 4、`same-title-different-content` 2、`distractor` 1。
- 冻结记录 `datasets/freeze.json` 固定 `datasetSha256`、`testSplitSha256`、`devSplitSha256`、`corpusManifestSha256`、每个文档的 `sha256`；`validate.py --freeze` 与 `run.py --freeze` 都会校验，任何改动都会失败退出。**调参前先冻结，改动题集或语料后必须重新生成冻结记录并重跑基线**，不能在旧基线上比较。
- 语料版本 `v1-2026-10-14` 与题集一起冻结；本任务不因为指标数字修改任何一个事实。

## 6. 精确复现命令

环境：`D:\Soft\Python\Anaconda5.3.0\python.exe`（Python 3.7.0，pytest 3.8.0）。所有命令在仓库根目录执行，密钥只通过环境变量传递。

```powershell
# 0) 设密钥：只写当前 shell 进程的环境变量，绝不写入仓库文件或命令行参数
#    下面这条从仓库内未跟踪的 jchatmind/src/main/resources/application-dev.yaml 读取第一个 api-key（embedding 块）
$env:EXOBRAIN_EVAL_EMBEDDING_API_KEY = (Select-String -Path jchatmind\src\main\resources\application-dev.yaml -Pattern 'api-key:\s*(\S+)').Matches[0].Groups[1].Value
$env:PYTHONIOENCODING = "utf-8"
# 1) 离线校验（题集 + 语料快照 + 冻结哈希）；失败时退出码 2
D:\Soft\Python\Anaconda5.3.0\python.exe evaluation/scripts/validate.py `
  --dataset evaluation/datasets/questions-v1.jsonl `
  --corpus-manifest evaluation/corpus/v1-2026-10-14/manifest.json `
  --freeze evaluation/datasets/freeze.json

# 2) 重新生成冻结记录（只在明确要改动题集时执行，之后必须重跑基线）
D:\Soft\Python\Anaconda5.3.0\python.exe evaluation/scripts/validate.py `
  --dataset evaluation/datasets/questions-v1.jsonl --emit-freeze

# 3) 离线测试（不访问网络与数据库）
D:\Soft\Python\Anaconda5.3.0\python.exe -m pytest evaluation/tests -q

# 4) 真实基线：配置放在仓库之外（示例见 evaluation/run-config.example.json，不含密钥）
D:\Soft\Python\Anaconda5.3.0\python.exe evaluation/scripts/run.py `
  --config <仓库外路径>\eval-config.json `
  --dataset evaluation/datasets/questions-v1.jsonl `
  --corpus-manifest evaluation/corpus/v1-2026-10-14/manifest.json `
  --freeze evaluation/datasets/freeze.json `
  --variant legacy-title-v1 --build-index --out evaluation/runs/legacy-title-v1

# 5) 候选策略（03 提供 body-v1 索引后）：同语料、同题集、同模型、同 k
D:\Soft\Python\Anaconda5.3.0\python.exe evaluation/scripts/run.py `
  --config <仓库外路径>\eval-config.json `
  --dataset evaluation/datasets/questions-v1.jsonl --variant body-v1 `
  --out evaluation/runs/body-v1

# 6) 对比（可比性检查 + 逐题差异）
D:\Soft\Python\Anaconda5.3.0\python.exe evaluation/scripts/compare.py `
  --baseline evaluation/runs/legacy-title-v1 --candidate evaluation/runs/body-v1 --k 3
```

说明与注意：

- `run.py` 从配置的 `embedding.apiKeyEnv`（默认 `EXOBRAIN_EVAL_EMBEDDING_API_KEY`）读密钥；配置里出现 `apiKey/api_key/key/token` 字段或 `Bearer ` 文本会被拒绝运行，run manifest 只记录环境变量名与是否存在。
- Embedding 服务 `https://emrank.tech/v1`（模型 `BAAI/bge-m3`，1024 维）前置了 Cloudflare：**urllib 默认 User-Agent 会被 403 / `error code: 1010` 拒绝**，客户端因此显式发送 `User-Agent: ExoBrain-Eval/0.1 (+https://github.com/2458438169/ExoBrain)`。
- 仓库内 `application-dev.yaml` 的 `cloudflare/bge-m3` 已失效（404），基线统一用 `BAAI/bge-m3`。
- pgvector 通过 `docker exec ... psql` 访问（本机 Python 环境没有 psycopg2）：容器 `exobrain-dev-pg`、库 `exobrain_eval`、用户 `exobrain`。

## 7. 真实基线结果（legacy-title-v1，2026-10-17）

- 运行：`runId = legacy-title-v1-v1-2026-10-14-20261017T102252Z`，代码提交 `0aa79ed`（运行时刻 HEAD；`evaluation/**` 变更尚未提交），语料 `v1-2026-10-14`（manifest `7999340d…`），题集 `questions-v1.jsonl`（`c795c7f6…`，测试集 `d60b933e…`），50 题全部 `OK`，服务故障 0、重试 0。
- 索引：23 个 section → 23 行，`kbId = b2026ba5-c6c0-5190-96df-028437751e73`，`indexRevision = legacy-title-v1/v1-2026-10-14/62408bc8895f`；Embedding 输入是章节标题，23 次调用共 122.6 s。
- 数据库：PostgreSQL 17.11 + pgvector 0.8.7；生产 SQL（`WHERE kb_id = … ORDER BY embedding <-> :q LIMIT 3`）在该规模下走 `Sort → Bitmap Heap Scan(idx_chunk_bge_m3_kb)`，**未使用 ivfflat 索引**；强制顺序扫描的结果与自然计划 50/50 完全一致（`planDiagnosticMismatch = 0`）。

| 范围 | n | Hit@1 | Hit@3 | Recall@1 | Recall@3 | MRR@1 | MRR@3 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 全部 | 40 | 0.650 | 0.850 | 0.637 | 0.812 | 0.650 | 0.738 |
| dev（调参集） | 23 | 0.565 | 0.826 | 0.565 | 0.783 | 0.565 | 0.674 |
| test（保留集） | 17 | 0.765 | 0.882 | 0.735 | 0.853 | 0.765 | 0.824 |

其它真实数字：微平均 Recall@3 = 0.773（全）/ 0.760（dev）/ 0.789（test）；跨文档覆盖率@3 = 0.812，全文档覆盖题比例 = 0.775；干扰片段命中率@3 = 0.857（6/7 题的干扰单元进入 top-3，`distractor` 类别那一题因此完全失败）；无答案 10 题**检索级拒绝率 0.000**（`topK=3` 无阈值，10 题全部返回 3 个片段）；每题检索延迟（含 Embedding）：n=50，p50 = 5467 ms，p95 = 5891 ms，max = 6055 ms，而每题 Embedding 单独看 p50 = 5285 ms——**延迟由远端 Embedding 调用主导**（每次约 5.2–5.9 s），本地向量查询只占几十毫秒。距离分布：有答案题 top1 L2 均值 0.806（min 0.426，max 0.997），无答案题均值 0.935（min 0.736，max 1.033）——**有重叠，不能直接拿单一 L2 阈值当「资料不足」判据**，需要 03 用 dev 集校准。10 道题的 top-3 内部出现完全相同的距离（并列），排名不可区分。

## 8. 已知局限

1. **只测检索，不测回答**：本任务不调用生成模型，回答级「资料不足」拒绝率、引用有效性与事实支持度均未测（`summary.noAnswer.answerLevelRejection = null`）。
2. **样本量小**：40 有答案题 + 10 资料不足题，2%–3% 的差异不具统计显著性；延迟 p95 只有 50 个样本且是线性插值点，不能当作 SLA。
3. **延迟受远端服务支配**：顺序执行、无缓存、限流未启用；Embedding 单次约 5.3 s，本地查询与网络耗时没有分离测量，生产环境（批量、缓存、并发）会明显不同。
4. **基线语义就是「标题建向量」**：命中判据要求正文里含事实，因此该策略对「事实只出现在正文」的问题天然吃亏（如 q013/q014/q020/q033 的失败），这正是 03 要解决的；不能把本基线分数当成 RAG 效果上限。
5. **合成文档加大难度**：语料含 1 份 `synthetic-eval-only` 干扰文档，与公开演示文档存在同名章节；公开演示资料单独构成的 section 数是 18（与公开基线「3 份文档 18 个 section」一致），加入合成文档后共 23。做对外汇报时若要复现公开基线，应剔除合成文档后重跑，并重新冻结。
6. **单模型单库单机**：只测 `BAAI/bge-m3`(1024) 与 pgvector 0.8.7 / PostgreSQL 17；换模型/距离算子/索引类型都必须重建索引并重新基线。
7. **题集由作者人工标注**：`insufficient-info` 的期望值是「资料未提供」，`expectedFacts` 不参与自动评分；检索级拒绝不能用它替代回答级拒绝。
8. **未测场景**：权限越界、真实超时/限流、索引发布失败与回滚由 03/04/06/07 的任务负责，本目录的故障处理只在离线夹具里覆盖（`UNAVAILABLE` 不计入拒绝）。

## 9. 给其它任务的接口

- **03（正文索引与结构化检索）**：用 `datasets/questions-v1.jsonl` 的 dev 集（30 题）调参，test 集（20 题）只在最终比较时用一次；用 `freeze.json` 保证同一题集；用 `runs/legacy-title-v1/summary.json` 的 `distanceSeparation` 校准阈值；命中判据与来源字段语义见第 3 节与 `contracts.md` 第 2、3 节。`body-v1` 适配器已就位（`scripts/exo_runtime.py`），只消费已发布索引，查询不到索引时返回 `UNAVAILABLE / INDEX_NOT_FOUND`，不会伪装成 `NO_MATCH`。
- **04（可核验引用）**：`payload` 是交给模型的正文，`headingPath/startLine/endLine/sourceHash` 是引用定位所需字段；`tests/test_scoring_known_rankings.py` 的 `hit_covers_unit` 夹具可用于检查「引用位置是否真的覆盖事实」。
- **08（CI 与工程文档）**：`validate.py`（离线、退出码 2/3）与 `pytest evaluation/tests` 可直接进 CI；真实运行需要密钥，CI 只跑离线部分。
