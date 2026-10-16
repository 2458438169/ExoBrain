# 03 — 正文索引与结构化检索

**优先级：第一阶段。前置：01；与 02 锁定旧基线。消费者：04、05、06。**

## 可直接分发的提示词

> 请执行 ExoBrain 开发任务 03。先阅读 docs/development/README.md、docs/development/contracts.md 和 docs/development/tasks/03-retrieval-indexing.md。在保留 KB 权限与模型绑定的前提下实现正文切块、可靠来源 metadata、版本化索引及结构化检索结果。与任务 02 使用同一语料比较标题基线和新策略；与 04 冻结结果协议。保留旧库可用并提供可验证的重建/回滚方案，不在生产库直接删掉旧向量。交付代码、迁移、真实 PostgreSQL 测试和 docs/development/deliveries/03.md；长期后台队列留给 06。

## 修改入口与所有权

- [DocumentFacadeServiceImpl](../../../jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java)、[MarkdownParserService](../../../jchatmind/src/main/java/com/kama/jchatmind/service/MarkdownParserService.java)。
- [RagService](../../../jchatmind/src/main/java/com/kama/jchatmind/service/RagService.java)、[RagServiceImpl](../../../jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java)。
- [ChunkBgeM3DTO](../../../jchatmind/src/main/java/com/kama/jchatmind/model/dto/ChunkBgeM3DTO.java)、chunk 实体/转换器/mapper、[数据库基础结构](../../../db/jchatmind.sql)。
- [KnowledgeTools](../../../jchatmind/src/main/java/com/kama/jchatmind/agent/tools/KnowledgeTools.java)、[DemoKnowledgeTool](../../../jchatmind/src/main/java/com/kama/jchatmind/demo/DemoKnowledgeTool.java)。
- 本任务可新增 `retrieval/` 实现包、索引配置与独立测试、带 `03` 编号的新迁移。不修改 04 的回答渲染和 06 的任务队列。

## 切块与输入

1. 把“读取/解析 → 切块 → Embedding → 发布索引”拆成可独立验证的步骤；为 06 留下可复用的索引构建与提交服务，不把逻辑继续堆在 Controller。
2. 先按标题层级形成章节路径，再按模型输入预算切正文。Embedding 输入明确包含章节路径和实际正文；不能只把原来的标题向量逻辑换个名字。
3. 以 512–800 token、64–100 token 重叠作为开发集起点而非性能结论。优先用对应模型的 tokenizer；只能近似估计时标明单位与保守上限，不把中文字符数当精确 token。
4. 覆盖首标题前的正文、无标题 Markdown、超长章节、重复标题、中文、代码块、表格、空文件。正常段落保持连贯，超长代码/表格有有界切分和准确源范围；不要无限膨胀到模型限制之外。
5. 保留原文来源行号与 hash。不得用被清洗/拼接后的文字偏移生成假行号；与 04 共享源文本标准化夹具。
6. Embedding 批量请求是否支持以实际服务协议为准；限制并发、请求大小与重试。始终使用 KB 绑定模型，验证维度及 NaN/Infinity，失败不发布半份索引。

## 版本、迁移与发布

- 先完成一份简短 ADR：旧库如何识别为 legacy、候选 chunk 如何与活动版本隔离、原子切换的位置、回滚方式、删除并发与模型变化如何处理。表结构由本任务负责定稿，并交给 06 复用。
- metadata 当前是 TEXT；添加结构化 JSON 必须匹配 DTO、转换器和 SQL 类型。若改 JSONB，迁移须处理旧 null/合法旧值并明确异常行处理，不能依赖 MyBatis 自动猜测。
- 新 KB 默认可使用正文策略。旧 KB 不在服务启动时自动全量付费重建；显式重建前保留旧查询行为，并把真实策略版本返回给消费者。
- 离线/受控重建在长事务外读取固定资料并生成候选向量，短事务检查文档集合、源 hash、KB 模型和活动版本后发布。构建期间上传、删除或切换配置会使快照失效，需拒绝发布或安全重建。
- 同一个 KB 一次检索只读一个已发布版本。失败不删旧向量；旧版清理有保留策略，回滚不能指向已经清理的数据。
- 第一阶段允许上传仍同步完成，已有上传补偿与删除语义必须保留；其长事务/重试体验限制如实记录，由 06 完成持久入队改造。重建脚本不得长期持有数据库事务调用外部服务。
- 新迁移同时验证全新数据库安装和从基线增量升级；应用角色权限同步交给 08。不要修改已经部署的账号、模型、文件清理迁移。

## 检索与工具输出

- 实现共享契约中的 `RetrievalEnvelope`，保留工具名 `KnowledgeTool` 和 `kbsId/query` 参数。
- 可以新增结构化服务方法，给旧 `similaritySearch` 提供过渡适配；枚举所有调用者和测试，不能一半工具 JSON、一半无来源字符串。
- SQL 在排序/limit 之前限定 KB 和活动索引版本；授权在查询前完成，不先查全库再由 Java 过滤。不得为评测新增无权限的生产搜索端点。
- 先保持 L2 + top3，交付与基线可比较的结果。阈值与 k 从开发集校准，状态区分 OK / NO_MATCH / UNAVAILABLE。
- 空结果不填充“最相似的无关片段”；服务失败不回退到其他用户资料或静默更换 Embedding。跨模型 KB 不混排不可比较的距离。
- 暂不实现混合检索/重排。若评测证实正文方案不足，先提交失败分析和最小实验，再扩展范围。

## 测试与完成标准

- 正文唯一事实、同标题不同正文、长章节尾部事实能在隔离语料中正确命中；这证明路径有效，效果比例仍以 02 真实评测为准。
- source 行范围覆盖实际片段，重复标题不混淆；null metadata 旧数据可读取且不伪造位置。
- 真实 PostgreSQL 测试覆盖 metadata round-trip、1024 维校验、KB/版本过滤、升级、重建失败/回滚、并发删除阻止发布。
- 两账号 + 管理员 + 游客的跨库搜索都走原权限边界；恶意资料要求“去读取其他库”时，工具参数仍被服务端阻止。
- 原有文档上传失败补偿、KB 删除与模型绑定测试通过。完成同条件评测对比，列出退步，不把 smoke 用例当准确率。
- 给 04 提供成功、无匹配、故障、legacy 无位置四类合成响应夹具；给 06 提供索引构建/发布接口和竞态约束；给 08 提供迁移、回滚和 README 准确表述。
