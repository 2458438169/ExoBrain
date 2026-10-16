# 02 — 可复现的检索评测基线

**优先级：第一阶段。前置：无；候选对比依赖 03。消费者：03、04、08。**

## 可直接分发的提示词

> 请执行 ExoBrain 开发任务 02。先阅读 docs/development/README.md、docs/development/contracts.md 和 docs/development/tasks/02-retrieval-evaluation.md。建立独立于生产用户数据的检索评测语料、问题集和脚本。先保存当前标题向量 + L2 + top3 的真实基线，再对同一语料比较新策略。明确区分离线脚本测试和真实 Embedding 检索结果，保留逐题失败与执行信息。不要修改线上知识库、为指标硬编码问答或自行改变生产检索逻辑。交付可运行命令、报告与 docs/development/deliveries/02.md。

## 代码与材料入口

- [三份公开资料与维护材料](../../public-demo/)；其中 `99_test_questions.md` 的六个问题可作种子。
- [RagServiceImpl](../../../jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java) 与 [向量 mapper](../../../jchatmind/src/main/resources/mapper/ChunkBgeM3Mapper.xml)：当前基线。
- 新文件由本任务负责：`evaluation/README.md`、`evaluation/corpus/`、`evaluation/datasets/`、`evaluation/scripts/`、`evaluation/tests/`。
- 共享构建配置和 CI 由 08 负责；评测适配器与 03 约定后接入，不新增未鉴权的公网检索接口。

## 题集与语料

1. 冻结一份可公开、带文件 SHA-256/来源许可的语料快照。只有 manifest 指定的原始文档进入索引；问题、答案、评测说明、开发文档一律不入库。
2. 第一版至少 40 题，其中至少 30 题有可定位证据、10 题资料不足。覆盖标题事实、只在正文出现的事实、中文改写、同标题不同内容、跨文档证据、相近但错误的干扰信息。
3. 按语义问题组划分开发集/保留测试集，例如 60% / 40%；同义改写不得一份在调参集、一份在测试集。记录 split，并在调参前冻结测试集。
4. 每题字段至少为 `id, split, category, question, answerable, corpusVersion, relevantEvidence, expectedFacts`。证据用稳定的文档 key、源 hash、原文行范围/事实单元标注，不能把会随重新分块变化的 chunk UUID 当唯一 gold。
5. 基于项目自身说明的问题应注明所依据的文档版本。两组算法比较时不同时更新语料中的事实，否则无法判断提升来自检索还是改写答案。
6. 权限越界、服务失败、超时属于单独的正确性场景，不混进普通语义检索准确率。合成私有资料必须有明显的测试标记与限定清理范围。

## 指标口径

| 指标 | 分母/算法 | 注意事项 |
| --- | --- | --- |
| Hit@k | 有答案题中，前 k 至少含一个正确证据单元的题数 / 总题数 | 不只按文档名命中判断 |
| Recall@k | 每题检出的不同 gold 证据单元数 / gold 总数，再按题平均 | 去掉重叠 chunk 对同一证据的重复计数 |
| MRR@k | 每题第一个正确证据排名的倒数，无命中为 0；再平均 | 只在有答案题统计 |
| 跨文档覆盖率 | 每题覆盖的必要证据单元 / 必要证据总数 | 返回一个正确文档不代表综合任务完成 |
| 无答案拒绝表现 | 无答案题中实际返回 NO_MATCH / 明确资料不足的比例 | 检索拒绝与回答拒绝分别统计；故障不算正确拒绝 |
| 引用有效性/支持度 | 引用 ID/位置有效比例；来源支持事实的人工核对 | 有效链接不等于支持回答 |
| 延迟 | 检索/生成/端到端分别报样本量、p50、p95 | 标注预热、并发、缓存及超时；小样本注明局限 |

证据命中判据在评分脚本和 README 中固定：命中片段必须覆盖标注的事实语句/证据范围，不能与某行轻微相交就算命中。回答事实支持用人工 rubric 复核一组样本并保留标签；可辅助用模型评分，但不能把模型自评当成客观准确率。

## 运行与比较

- 先在隔离数据库按旧策略建立索引：标题 Embedding、KB 过滤、L2、top3。可在 mapper 返回处保留来源 ID 供评分，但不得暗中换为正文向量或增加重排。
- 03 候选初次比较保持同一语料、问题、模型/服务版本、向量维度、k 和运行环境。后续改变 topK、阈值、分块大小的实验另列，不能混称单变量改进。
- 检索效果先独立于回答生成测量。只有需要评估最终回答时才调用 LLM，固定提示词版本并保存真实输出；预算/速率由配置限制。
- 真实模型不可用时先完成数据验证器、统计脚本和确定性适配器测试；报告明确“未取得真实模型基线”，不把固定向量夹具跑出的分数发布成 RAG 效果。
- 输出 run manifest：代码提交、语料 hash、题集版本、模型标识、策略配置、运行时间、随机种子（适用时）、硬件/并发、逐题检索/回答、失败/重试、原始 JSONL 和汇总。
- 生产凭据放仓库外；报告不包含 Cookie、API Key、内部地址或私人内容。需要共享的结果只来自可公开/合成语料。

建议实现以下 CLI 合约，并在交付时填入真实可执行说明：

~~~text
python evaluation/scripts/validate.py --dataset <questions.jsonl>
python evaluation/scripts/run.py --config <仓库外配置> --dataset <questions.jsonl> --variant legacy-title-v1 --out <结果目录>
python evaluation/scripts/run.py --config <仓库外配置> --dataset <questions.jsonl> --variant body-v1 --out <结果目录>
python evaluation/scripts/compare.py --baseline <旧结果> --candidate <新结果>
~~~

这些脚本当前尚不存在，是本任务交付物。脚本不得要求开发者把密钥写到命令行或提交的配置中。

## 完成标准

- 题集/来源合法性验证可离线运行，重复 ID、失效行号、hash 漂移、缺少 gold、split 重叠能失败退出。
- 指标计算用小型已知排名夹具核对，覆盖空结果、多证据、重复片段、超时；脚本有有意义的自动测试。
- 至少交付真实旧策略基线；候选完成后补同条件逐题对比。无真实服务时交付状态必须为“脚手架完成，效果待测”。
- 对每类退步/失败保留代表问题与原因。不要以“必须提升某百分比”为通过条件；实际效果不足时由 03 继续调整或保持原默认策略。
- 02 向 03 提供调参集与基线，向 04 提供来源支持检查，向 08 提供可公开报告及精确复现命令。全套开发基准不自动发布到产品知识库。
