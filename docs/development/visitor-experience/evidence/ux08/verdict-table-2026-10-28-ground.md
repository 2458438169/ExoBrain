# 逐题判定与分组合计（由 verdicts 数据生成，合计不手写）

判定规则：pass-criteria-2026-10-28.md（每题只计一次；串题/错引/无依据承诺/失败/超预算一律不通过）

数据来源：`verdicts-2026-10-28-ground.json`

## 主运行（shared，全部 30 题，每题最多 2 次）

用途：正式整组结果。运行文件 `run-2026-10-28-grounded-30.json`；提示词 sha256 `e2c639e59640d29a`；语料 `v4-2026-10-28`；会话模式 `shared`；消息窗口 `40`；题间间隔 1.0 s；最多尝试 2 次；退避 8.0 s。

运行事实：30 题（成功 16 / 失败 14）；尝试 53 次（成功 16 / 失败 37）；模型调用 85 次、失败 37 次；守卫拒绝 0 次；首次尝试成功 7 题。

**通过 15 / 30。**

| 题号 | runId | 终态 | 尝试 | 通道 | 字数/预算 | 引用 | 判定 | 理由 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q01 | `None` | SUCCEEDED | 1 | groundedAnswer | 143/350 | 2 | 通过 | 一句话定位挂 01 L9-15；三要点与三条体验路径分别挂 01 L9-15、01 L19-23，未越界。 |
| Q02 | `None` | SUCCEEDED | 1 | groundedAnswer | 274/350 | 2 | 通过 | 定位粒度挂 03 L81-83，两条通道说明挂 03 L70-77；第 3 条与第 1 条同源，共用 [1]，属合法去重。 |
| Q04 | `None` | SUCCEEDED | 2 | groundedAnswer | 364/350 | 3 | **不通过** | 长度 364/350 超预算（冻结口径默认不通过）；事实与引用本身无错。 |
| Q05 | `None` | SUCCEEDED | 2 | groundedAnswer | 171/300 | 1 | 通过 | 能解决 / 不能解决 / 引用≠正确 三点均出自 01 L27-29。 |
| Q06 | `None` | SUCCEEDED | 1 | groundedAnswer | 205/350 | 1 | 通过 | 幂等键、浏览器实测、边界三句均出自 02 L8-22，未承诺外部 exactly-once。 |
| Q09 | `None` | SUCCEEDED | 1 | groundedAnswer | 187/350 | 1 | 通过 | 有界锁等待、独立调度器 + SET LOCAL lock_timeout、边界三句均出自 02 L55-71。 |
| Q11 | `None` | SUCCEEDED | 1 | groundedAnswer | 122/300 | 2 | 通过 | 两句分别挂 01 L27-29 与 03 L81-83，只陈述「引用不代表结论正确」，没有新增因果。 |
| Q12 | `None` | SUCCEEDED | 2 | groundedAnswer | 131/300 | 2 | 通过 | 管理员不可读他人资源挂 01 L33-36 与 03 L31-33；游客范围挂 01 L33-36。 |
| Q14 | `None` | SUCCEEDED | 2 | groundedAnswer | 56/300 | 0 | 通过 | 资料不足走独立结果：明确说没有作者履历，零引用，不编造。 |
| Q18 | `None` | SUCCEEDED | 2 | groundedAnswer | 38/300 | 1 | 通过 | 模型/检索/工具/落库步骤及耗时出自 01 L19-23。 |
| Q20 | `None` | SUCCEEDED | 2 | groundedAnswer | 134/300 | 1 | 通过 | Hit@3 / Recall@3 / MRR@3 与配套资产均出自 03 L22-31。 |
| Q22 | `None` | SUCCEEDED | 2 | groundedAnswer | 150/350 | 1 | 通过 | fencing 前进、写回两条件与整笔回滚均出自 02 L26-42。 |
| Q24 | `None` | SUCCEEDED | 2 | groundedAnswer | 203/350 | 2 | 通过 | 基于 JChatMind、技术栈、保留能力均对到 03 L3-5 与 03 L9-18；列举不完整但无错误断言。 |
| Q26 | `None` | SUCCEEDED | 2 | groundedAnswer | 55/300 | 1 | 通过 | 资料不足走独立结果、不挂引用，出自 03 L70-77。 |
| Q27 | `None` | SUCCEEDED | 1 | groundedAnswer | 163/350 | 2 | 通过 | 来源粒度挂 01 L9-15，契约边界挂 03 L81-83，运行记录挂 01 L9-15。 |
| Q28 | `None` | SUCCEEDED | 1 | groundedAnswer | 155/300 | 1 | 通过 | 幂等键复用、浏览器实测、边界三句均出自 02 L8-22。 |
| Q03 | `None` | FAILED | 2 | — | 0/350 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q07 | `None` | FAILED | 2 | — | 0/350 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q08 | `None` | FAILED | 2 | — | 0/350 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q10 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q13 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q15 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q16 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q17 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q19 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q21 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q23 | `None` | FAILED | 2 | — | 0/350 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q25 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q29 | `None` | FAILED | 2 | — | 0/250 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |
| Q30 | `None` | FAILED | 2 | — | 0/300 | 0 | **不通过** | 两次尝试都 EXECUTION_FAILED（上游 429），没有正文，按失败计。 |

## 补跑（per-question，仅主运行失败的 14 题，每题最多 3 次）

用途：补跑结果，不能替换主运行。运行文件 `run-2026-10-28-grounded-recovery.json`；提示词 sha256 `e2c639e59640d29a`；语料 `v4-2026-10-28`；会话模式 `per-question`；消息窗口 `40`；题间间隔 5.0 s；最多尝试 3 次；退避 30.0 s。

运行事实：14 题（成功 14 / 失败 0）；尝试 23 次（成功 14 / 失败 9）；模型调用 58 次、失败 8 次；守卫拒绝 1 次；首次尝试成功 7 题。

**通过 12 / 14。**

| 题号 | runId | 终态 | 尝试 | 通道 | 字数/预算 | 引用 | 判定 | 理由 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q03 | `None` | SUCCEEDED | 1 | groundedAnswer | 353/350 | 3 | **不通过** | 长度 353/350 超预算（冻结口径默认不通过）；列举已不再越界，事实与引用本身无错。 |
| Q07 | `None` | SUCCEEDED | 1 | groundedAnswer | 251/350 | 1 | 通过 | 协作式取消、检查点停止、迟到写入回滚三句均出自 02 L26-42。 |
| Q08 | `None` | SUCCEEDED | 1 | groundedAnswer | 176/350 | 1 | 通过 | RUNNING 重启记 INTERRUPTED、不自动重放、恢复范围三句均出自 02 L46-51。 |
| Q10 | `None` | SUCCEEDED | 2 | groundedAnswer | 72/300 | 1 | 通过 | 明确「不是流式」，两句均出自 03 L44-48。 |
| Q13 | `None` | SUCCEEDED | 1 | groundedAnswer | 53/300 | 0 | 通过 | 合格拒答：明确说没有客户数与成本数据，零引用。mustNot 命中的「节省」出现在否认句里，属正则误伤，不算编造。 |
| Q15 | `None` | SUCCEEDED | 3 | groundedAnswer | 78/300 | 0 | 通过 | 合格拒答：明确说资料没写支持的文件格式清单，零引用。 |
| Q16 | `None` | SUCCEEDED | 3 | groundedAnswer | 138/300 | 2 | 通过 | 「文档名、行号范围与原文片段」直接出自 03 L81-83；协议说明挂 03 L70-77。 |
| Q17 | `None` | SUCCEEDED | 3 | groundedAnswer | 115/300 | 2 | **不通过** | 回答了本题之外的问题：第 3 条「不同供应商或不同模型的向量不能混在一个索引里，换模型必须重新导入」是换向量模型的答案，与「导入失败怎么处理」无关。该句有真实来源，但冻结口径要求只回答当前问题，不因事实无误豁免。 |
| Q19 | `None` | SUCCEEDED | 2 | groundedAnswer | 125/300 | 2 | 通过 | 游客范围与管理员隔离分别挂 01 L33-36、03 L31-33。 |
| Q21 | `None` | SUCCEEDED | 1 | groundedAnswer | 52/300 | 1 | 通过 | 「不能直接换，必须重新导入」出自 03 L44-48。 |
| Q23 | `None` | SUCCEEDED | 1 | groundedAnswer | 183/350 | 1 | 通过 | 有界可放弃、预算内重试 + 无锁确认、边界三句均出自 02 L55-71。 |
| Q25 | `None` | SUCCEEDED | 2 | groundedAnswer | 290/300 | 3 | 通过 | 区分两条通道：旧协议 [n] 对应 citationIds 第 n 项（03 L63-66）；新协议无该参数、编号由服务端排（03 L70-77）。 |
| Q29 | `None` | SUCCEEDED | 1 | groundedAnswer | 174/250 | 1 | 通过 | 三句均出自 01 L40-42；因果连接在原句里就有（原文即「因此」）。 |
| Q30 | `None` | SUCCEEDED | 1 | groundedAnswer | 183/300 | 2 | 通过 | 点开来源的三个可见项与契约边界出自 03 L81-83，直接提问路径出自 01 L19-23。 |

## 跨条件诊断（不作整组达标成绩）

跨条件诊断：每题取主运行或补跑里已通过的那一次，只用于内容诊断，不作为整组达标成绩。

**通过 27 / 30**；未通过：Q03、Q04、Q17。

## 合计对照

| 数据组 | 通过 | 用途 |
| --- | ---: | --- |
| 主运行（shared，全部 30 题，每题最多 2 次） | **15/30** | 正式整组结果 |
| 补跑（per-question，仅主运行失败的 14 题，每题最多 3 次） | **12/14** | 补跑结果，不能替换主运行 |
| 跨条件诊断（每题取已通过的一次） | **27/30** | 内容诊断，不作整组达标成绩 |

