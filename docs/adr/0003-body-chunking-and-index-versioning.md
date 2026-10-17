# ADR 0003 — 正文切块、来源位置与索引版本化

- 状态：已实现（决策已在当前代码中落地；任务 06 复用本决策的构建/发布边界）
- 范围：知识库的正文索引（切块、Embedding 输入、来源位置、版本化重建与检索读取）
- 相关契约：[共享契约](../development/contracts.md) §2（`RetrievalEnvelope` / `RetrievalHit` / `SourceLocation`）与 §3（来源哈希、行号、索引版本与候选发布边界）
- 相关位置：`retrieval/`（`SourceText`、`BodyChunker`、`IndexVersionPolicy`、`IndexPublisher`、`RetrievalIndexServiceImpl`、`RetrievalServiceImpl`、`LegacyTitleIndexer`）、`deploy/migrations/20261017-03-body-index.sql`、`service/impl/DocumentFacadeServiceImpl.java`、`resources/mapper/ChunkBgeM3Mapper.xml`、`agent/tools/KnowledgeTools.java`、`demo/DemoKnowledgeTool.java`

## 1. 问题

基线只对**章节标题**做向量（`DocumentFacadeServiceImpl.processMarkdownDocument`：`embed(title)`，chunk 保存整段正文，`metadata` 为空）。后果：

1. **标题没有的事实检索不到**：问题问的是正文里的内容、而标题不含该信息时，向量距离无法表达“这段正文相关”。
2. **没有可核验的来源位置**：chunk 只有 `doc_id`，回答引用只能打开整份文档；重复标题的章节无法区分。
3. **无法安全重建**：`chunk_bge_m3` 没有版本概念，重建只能先删后建——中间窗口内检索结果是空的，失败则旧索引已丢。
4. **没有拒绝策略**：查询永远返回“最相似的 3 段”，即使毫不相关；调用方也无法区分“没有资料”与“检索服务挂了”。

同时有四条硬约束：旧库必须继续可用（不能启动时自动全量付费重建、不能静默改变行为）；搜索必须服务端限定 KB 与权限；来源位置必须来自原文真实偏移（不能从清洗后的文字反推）；未校准前不得伪造“相似度/置信度”。

## 2. 考虑过的方案

| 方案 | 做法 | 否决原因 |
| --- | --- | --- |
| A. 维持标题向量，只补 metadata 位置 | 只把行号写进 metadata | 正文唯一事实仍然检索不到（问题 1 未解决）；位置来自章节范围而非 chunk 内容，容易在“整段章节”上给出过宽的行号 |
| B. 整份文档一个向量 | 每份文档一个 chunk | 无法给出准确来源范围；超出模型上下文；长文档尾部事实被平均掉 |
| C. 固定字符数滑窗切块 | 按 1000 字符硬切 + 重叠 | 会把标题正文割裂、破坏段落与代码块/表格结构；中文与英文的 token 密度差 2 倍以上，字符数不是预算单位 |
| D. 引入独立向量库/外部索引服务 | 另起索引存储 | 第一阶段明确不引入新基础设施；pgvector 已在用，事务与权限边界也更简单 |
| E. 重建时先删旧 chunk 再写新 chunk | 原地替换 | 失败即丢索引，检索窗口为空（违反“失败保持旧索引可用”） |
| F. 切块策略 + 版本化发布 + 结构化检索（本 ADR） | 见下一节 | **采用** |

## 3. 最终选择

### 3.1 标准化与来源定位

`SourceText.normalize`：UTF-8 解码 → 去掉首个可选 BOM → CRLF/CR 统一为 LF。`sha256` 针对**该文本再以 UTF-8 编码**计算（契约 §3），只用于比对，不改变原文接口展示的换行。行号由同一份标准化文本的行表映射（`lineOf` / `lineRange`），1 基、两端包含，满足 `1 <= startLine <= endLine <= 总行数`。行号来自 flexmark AST 的**原文 offset**，不是从清洗后的文字长度反推。

### 3.2 切块

先用 flexmark AST 得到标题层级路径与章节源范围（只遍历顶层节点，与基线解析器的可见范围一致；子章节正文归属最近的标题，不在父章节重复索引），再按预算切正文：

- 预算单位是 **estimated token**（保守字符估算：CJK 1 字符 ≈ 1，其余 2 字符 ≈ 1），开发集起点 512–800、重叠 64–100；另有 `max-chunk-characters`（默认 4096）字符硬上限。**不把中文字符数当精确 token**，也不把估算值当成 tokenizer 的结论。
- 段落是一个切块单元；超长段落先按行、单行仍超长再按字符窗口有界切分（超长代码块/表格因此有界且源范围准确）。chunk 文本一律取自原文 offset 区间，因此不会出现“行号覆盖全文、正文里却无标识地删句”。
- Embedding 输入 = 标题路径 + 实际正文（`embeddingText`），正文本身单独存放在 `content`。
- 空文件、无标题 Markdown、首标题前正文（路径为空数组）都有确定行为：空文件不产生 chunk（上传路径继续报“没有可索引的章节”）。

### 3.3 版本与发布

- `indexVersion` 是语义版本：`legacy-title-v1`（基线标题向量）与 `body-v1`（正文切块）。
- `indexRevision` 是一次发布记录（表 `exobrain_index_revision`，状态 `DRAFT / PUBLISHED / SUPERSEDED / FAILED`）；每个 KB 的活动版本记录在 `exobrain_kb_index_state`，同库最多一个 `PUBLISHED`（部分唯一索引 `exobrain_index_revision_active` 强制）。
- `chunk_bge_m3` 增量列：`revision_id`、`index_version`、`chunk_ordinal`、`source_hash`、`start_line`、`end_line` + `(kb_id, revision_id)` 索引。旧行全部保持 NULL，不需要回填、不需要重写向量。

**legacy 如何识别**：`IndexVersionPolicy.resolve(state, hasLegacyChunks)`

1. 有活动状态行 → 用其 `active_index_version` 与 `active_revision_id`；
2. 无状态行、但该 KB 已有 `revision_id IS NULL` 的 chunk → 保持 `legacy-title-v1`（**不静默切换**：新上传继续按标题向量写入，迁移必须显式重建）；
3. 无状态行且没有任何 chunk（新库）→ `body-v1`。

**候选与活动版本如何隔离**：候选（`IndexCandidate`）只是内存对象：源文档快照 + 切块 + Embedding 结果，构建期间不写任何索引行，也不持有数据库事务。Embedding 调用永远在事务之外。

**原子切换的位置**：`IndexPublisher.publish`（一个短事务）：

1. `SELECT ... FOR UPDATE` 锁住 `knowledge_base` 行 → 串行化同库发布，并与 KB 删除互斥；
2. 事务内**重新核对**：候选文档集合 == 线上 `document` 集合；候选覆盖的文档都有 chunk（自身或从基础版本复制）；候选摘要与内容一致；基础版本仍是当前活动版本（部分重建时）；该 KB 绑定的向量模型仍是候选使用的模型；
3. 写入 DRAFT 版本 → 复制未变化文档的 chunk（库内 `INSERT ... SELECT`，不调用外部服务）→ 插入新 chunk → `SUPERSEDED` 旧版本 → `PUBLISHED` 新版本（`published_at`）→ `UPSERT` 活动状态行。

检索只读“状态行指向的那个版本”（或 legacy 行），因此**要么看到旧版本、要么看到新版本**，不会读到混合结果。

**回滚方式**：`IndexPublisher.rollback(kbId, revisionId)` 重新激活一个曾经发布成功的版本，但必须先确认它仍存在数据（`countChunksForRevision > 0`），否则拒绝——**回滚不能指向已清理的数据**。清理（`cleanupSuperseded`）只删除 `SUPERSEDED` 版本，活动版本由状态行的外键（`ON DELETE RESTRICT`）与查询条件双重保护。

**删除并发**：文档/KB 在快照之后被删除会让候选的文档集合与线上不一致，发布被拒绝（任务 06 据此安全重建）；chunk 对 `document` 的外键是级联删除，因此已发布版本中的文档被删除后不会留下可检索的孤儿索引；删除文档后 `refreshActiveRevision` 按事实重算版本计数，并把 `source_set_hash` 置空（不保留一个已不代表当前文档集合的 hash）。

**模型变化**：候选记录 `model_catalog_id/model_name/dimensions`；发布时若 KB 绑定已改变则拒绝。检索始终使用 KB 当前绑定的模型，绑定不可用即 `UNAVAILABLE`，不回退到默认模型、不与其他向量空间混排。相同维数不等于相同向量空间，因此不比较跨模型距离。

### 3.4 检索与工具输出

`RetrievalServiceImpl`：授权判断（调用方提供）→ KB 级模型绑定 → 当前活动索引版本 → 查询向量 → `searchScoped`（SQL 里限定 `kb_id` + `revision_id`，按 L2 排序后 limit）。状态：`OK`（至少一个 hit）/ `NO_MATCH`（空索引或超过校准阈值）/ `UNAVAILABLE`（模型、向量服务或索引不可用）；后两者 hits 必须为空。未授权是访问失败（`RetrievalAccessDeniedException`），不返回信封。重排与混合检索不在本阶段。`KnowledgeTool(kbsId, query)` 与演示工具的返回值统一改为 `RetrievalEnvelope` 的 JSON（旧的 `RagService.similaritySearch` 保留为过渡适配，不可用时抛异常而不是返回空列表）。

## 4. 代价

1. **一次上传会调用多次 Embedding**（每个 chunk 一次，基线是每个标题一次）：上传耗时与费用上升；批量请求、并发与重试留给任务 06。
2. **上传路径仍是长事务**：文档记录、文件路径、候选构建、Embedding 与发布在同一个上传事务内（发布本身是短的，但它加入了这个事务）。长事务期间会持有 KB 行锁与文档行；任务 06 负责改成持久化导入 + 短事务。
3. **并发上传同一 KB 可能失败一次**：第二个上传的候选文档集合可能已过期，发布会拒绝并要求重试（旧实现只是默默多写一批行）。这是有意的取舍：宁可拒绝，也不发布与线上不一致的索引。
4. **legacy 库不会自动变好**：未显式重建的库继续使用标题向量，新上传也遵循该库当前策略（`legacy-title-v1`），因此那些库中新增文档同样没有来源位置。需要重建才能获得正文检索效果。
5. **同一 KB 的一次检索只读一个版本**：跨版本比较、增量补丁式索引不在本阶段；重建是整库发布。
6. **估算 token 不等于真实 token**：预算只是保守上界近似；换 tokenizer/换模型时必须重新评估预算与切块效果。
7. **旧引用可能指向被清理的版本**：引用位置一旦被 04 渲染，历史消息仍保留 `indexRevision`；清理掉该版本后，引用只能按“来源已变更/不可核验”处理，不能自动落到另一个版本上。

## 5. 验证

- 单元（默认 profile，`com.kama.jchatmind.retrieval`）：标准化（BOM/CRLF/hash/行号）、切块全部边界（首标题前正文、无标题、超长章节、重复标题、中文、代码块、表格、空文件）、envelope JSON 字段完整性、策略解析分支、检索状态与过滤（桩件）。
- 真实 PostgreSQL 17 + pgvector（`-Ppostgres-it`，一次性隔离数据库）：迁移增量与幂等、metadata round-trip、1024 维校验、KB/版本过滤（legacy 与 body 不混用）、legacy 行不伪造位置、重建失败保留旧索引与 FAILED 记录、删除阻止发布、KB 锁串行化发布、回滚拒绝已清理数据、KB 删除清理索引。
- 效果：用共享黄金夹具 + 真实 Embedding 端点验证“正文唯一事实、同标题不同正文、长章节尾部事实”能命中正确来源与行号；无密钥时如实标记为未执行。
- 具体命令与真实计数见 `docs/development/deliveries/03.md`。

## 6. 何时重新评估

- 任务 02 的真实评测显示正文切块仍不达标（Recall@k / 资料不足表现）：先提交失败分析，再考虑混合检索、重排或调整预算，而不是直接换基础设施。
- 引入 tokenizer 精确计数或换 Embedding 模型时：重算 `chunk-*` 预算、重新校准距离阈值，并按新 `indexVersion` 重建（不得复用旧版本号）。
- 需要异步/增量索引或跨版本比较时：由任务 06 在现有 `prepareRebuild/indexDocument → publish` 接口上扩展，不要绕过发布前的重新核对。
- 需要距离阈值参与“资料不足”判定时：必须用开发集校准后再打开 `app.retrieval.max-distance`，并记录校准数据；在此之前保持 `null`（未启用）。