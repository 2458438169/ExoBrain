# 检索评测对比：legacy-title-v1 → body-v1

- 基线: evaluation/runs/legacy-title-v1（legacy-title-v1-v1-2026-10-14-20261017T102252Z）
- 候选: evaluation/runs/body-v1（body-v1-v1-2026-10-14-20261018T111843Z）
- k = 3；可比性: 一致

| 范围 | 指标 | 基线 | 候选 | 差值 |
| --- | --- | --- | --- | --- |
| 全部 | Hit@3 | 0.850 | 0.600 | -0.250 |
| 全部 | Recall@3 | 0.812 | 0.588 | -0.225 |
| 全部 | MRR@3 | 0.738 | 0.562 | -0.175 |
| 全部 | 跨文档覆盖率@3 | 0.812 | 0.588 | -0.225 |
| 全部 | 微平均 Recall@3 | 0.773 | 0.591 | -0.182 |
| 开发集 | Hit@3 | 0.826 | 0.783 | -0.043 |
| 开发集 | Recall@3 | 0.783 | 0.761 | -0.022 |
| 开发集 | MRR@3 | 0.674 | 0.717 | +0.043 |
| 开发集 | 跨文档覆盖率@3 | 0.783 | 0.761 | -0.022 |
| 开发集 | 微平均 Recall@3 | 0.760 | 0.760 | +0.000 |
| 保留测试集 | Hit@3 | 0.882 | 0.353 | -0.529 |
| 保留测试集 | Recall@3 | 0.853 | 0.353 | -0.500 |
| 保留测试集 | MRR@3 | 0.824 | 0.353 | -0.471 |
| 保留测试集 | 跨文档覆盖率@3 | 0.853 | 0.353 | -0.500 |
| 保留测试集 | 微平均 Recall@3 | 0.789 | 0.368 | -0.421 |
| 全部 | 无答案检索拒绝率 | 0.000 | 0.000 | +0.000 |
| 全部 | 服务故障题数 | 0 | 0 | +0 |
| 全部 | 无答案题数 | 10 | 10 | +0 |
| 保留测试集 | 无答案检索拒绝率 | 0.000 | 0.000 | +0.000 |
| 保留测试集 | 服务故障题数 | 0 | 0 | +0 |
| 保留测试集 | 无答案题数 | 3 | 3 | +0 |
| 全部 | 检索延迟 p50 (ms) | 5467.427 | 5181.592 | -285.835 |
| 全部 | 检索延迟 p95 (ms) | 5891.352 | 5343.067 | -548.285 |
| 全部 | 延迟样本量 | 50 | 50 | +0 |

## 可比性检查

- 一致 题集 SHA-256: 基线=c795c7f69d33fa56c8cafe8535300490057f3ec69bbdeb587958c535ae5df08f 候选=c795c7f69d33fa56c8cafe8535300490057f3ec69bbdeb587958c535ae5df08f
- 一致 语料版本: 基线=v1-2026-10-14 候选=v1-2026-10-14
- 一致 题数: 基线=50 候选=50
- 一致 Embedding 模型: 基线=BAAI/bge-m3 候选=BAAI/bge-m3
- 一致 k 取值: 基线=[1, 3] 候选=[1, 3]
- 一致 topK: 基线=3 候选=3

## 逐题差异（前 12 条，按题目 ID 排序）

- q002 top1-document: eval-synthetic-java-notes → exobrain-overview
- q003 top1-document: java-backend-faq → exobrain-overview
- q014 top1-document: java-backend-faq → rag-principles
- q018 top1-document: eval-synthetic-java-notes → java-backend-faq
- q020 top1-document: java-backend-faq → rag-principles
- q022 top1-document: eval-synthetic-java-notes → rag-principles
- q027 top1-document: eval-synthetic-java-notes → exobrain-overview
- q028 top1-document: java-backend-faq → exobrain-overview
- q029 top1-document: exobrain-overview → java-backend-faq
- q031 top1-document: eval-synthetic-java-notes → java-backend-faq
- q032 top1-document: java-backend-faq → eval-synthetic-java-notes
- q033 top1-document: java-backend-faq → exobrain-overview

## 说明

- 两组结果必须来自同一语料、题集、模型版本与 k；任一项不同则不能声称单变量提升。
- 指标差异不等于回答质量差异：回答事实支持度需要单独的人工 rubric 复核。

报告已写入: evaluation/runs/body-v1/comparison.md / evaluation/runs/body-v1/comparison.json
