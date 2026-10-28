# 逐题判定与分组合计（由 verdicts 数据生成，合计不手写）

判定规则：pass-criteria-2026-10-28.md（每题只计一次；串题/错引/无依据承诺/失败/超预算一律不通过）

数据来源：`verdicts-2026-10-28-v5.json`

## 固定条件整组（shared 会话、30 题、题间 1 s、模型请求最小间隔 11 s、每题最多 3 次）

用途：正式整组结果。运行文件 `run-2026-10-28-grounded-30-v5.json`；提示词 sha256 `7d0cbbbfb920e652`；语料 `v5-2026-10-28`；会话模式 `shared`；消息窗口 `40`；题间间隔 1.0 s；最多尝试 3 次；退避 8.0 s。

运行事实：30 题（成功 30 / 失败 0）；尝试 30 次（成功 30 / 失败 0）；模型调用 62 次、失败 0 次；守卫拒绝 0 次；首次尝试成功 30 题。

**通过 30 / 30。**

| 题号 | runId | 终态 | 尝试 | 通道 | 字数/预算 | 引用 | 判定 | 理由 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q01 | `None` | SUCCEEDED | 1 | groundedAnswer | 130/350 | 2 | 通过 | 一句话定位挂 01 L9-15，三要点同段，三条体验路径挂 01 L19-23；130 字达标。 |
| Q02 | `None` | SUCCEEDED | 1 | groundedAnswer | 211/350 | 2 | 通过 | 定位粒度挂 03 L81-83，新协议的短引校验挂 03 L70-77，契约边界挂 L81-83；211 字达标。 |
| Q03 | `None` | SUCCEEDED | 1 | groundedAnswer | 200/350 | 3 | 通过 | 只列继承（Agent 主循环，03 L9-18）与新增的一项（可核验引用，03 L22-31）并标明「之一」，不再越界列举；200/350。 |
| Q04 | `None` | SUCCEEDED | 1 | groundedAnswer | 176/350 | 2 | 通过 | 可信任依据挂 01 L9-15（含运行记录），边界挂 01 L27-29；176/350 达标。 |
| Q05 | `None` | SUCCEEDED | 1 | groundedAnswer | 99/300 | 1 | 通过 | 能解决 / 不能解决两点同出自 01 L27-29；99 字。 |
| Q06 | `None` | SUCCEEDED | 1 | groundedAnswer | 154/350 | 1 | 通过 | 去重键、同键返回 runId、边界三句均出自 02 L8-22，未承诺外部 exactly-once。 |
| Q07 | `None` | SUCCEEDED | 1 | groundedAnswer | 230/350 | 1 | 通过 | 取消三步与边界（不撤回已发请求、不承诺立即停止）均出自 02 L26-42。 |
| Q08 | `None` | SUCCEEDED | 1 | groundedAnswer | 176/350 | 1 | 通过 | QUEUED 重启重领、RUNNING 记 INTERRUPTED、恢复范围三句均出自 02 L46-51。 |
| Q09 | `None` | SUCCEEDED | 1 | groundedAnswer | 175/350 | 1 | 通过 | 共用线程的危害、独立调度器方案、真实 PostgreSQL 用例均出自 02 L55-71。 |
| Q10 | `None` | SUCCEEDED | 1 | groundedAnswer | 75/300 | 1 | 通过 | 明确「不是」，两句均出自 03 L44-48。 |
| Q11 | `None` | SUCCEEDED | 1 | groundedAnswer | 79/300 | 2 | 通过 | 引用≠结论正确，两句分别挂 03 L81-83 与 01 L27-29，无新增因果。 |
| Q12 | `None` | SUCCEEDED | 1 | groundedAnswer | 81/300 | 2 | 通过 | 管理员不可读他人资源挂 01 L33-36，资源隔离挂 03 L31-33。 |
| Q13 | `None` | SUCCEEDED | 1 | groundedAnswer | 57/300 | 0 | 通过 | 合格拒答：明确说没有客户数或成本数据，零引用。mustNot 的「节省」出现在否认句里，属正则误伤。 |
| Q14 | `None` | SUCCEEDED | 1 | groundedAnswer | 50/300 | 0 | 通过 | 合格拒答：明确说没有作者履历，零引用。 |
| Q15 | `None` | SUCCEEDED | 1 | groundedAnswer | 49/300 | 0 | 通过 | 合格拒答：明确说没有文件格式清单，零引用。 |
| Q16 | `None` | SUCCEEDED | 1 | groundedAnswer | 86/300 | 2 | 通过 | 定位粒度直接出自 03 L81-83；短引校验出自 03 L70-77，属同一主题的协议约束。 |
| Q17 | `None` | SUCCEEDED | 1 | groundedAnswer | 38/300 | 1 | 通过 | 只答导入失败的处理：入队、失败重试、固定错误码、状态可查询，全部出自 03 L22-31；换向量模型那句无关内容已消失（79→38 字）。 |
| Q18 | `None` | SUCCEEDED | 1 | groundedAnswer | 108/300 | 2 | 通过 | 查看过程可见项挂 01 L19-23，运行记录挂 01 L9-15。 |
| Q19 | `None` | SUCCEEDED | 1 | groundedAnswer | 124/300 | 1 | 通过 | 游客范围、公开资料发布边界、账号隔离三句均出自 01 L33-36。 |
| Q20 | `None` | SUCCEEDED | 1 | groundedAnswer | 58/300 | 1 | 通过 | 三项指标与配套资产均出自 03 L22-31。 |
| Q21 | `None` | SUCCEEDED | 1 | groundedAnswer | 68/300 | 2 | 通过 | 不能直接换（03 L44-48）与换绑守卫（03 L31-33）分挂，无串题。 |
| Q22 | `None` | SUCCEEDED | 1 | groundedAnswer | 189/350 | 2 | 通过 | fencing 前进与写回条件挂 02 L26-42；第 3 条给的是同一设计的验证边界（03 L37-40），属该问题的适用范围，不是别题答案。 |
| Q23 | `None` | SUCCEEDED | 1 | groundedAnswer | 139/350 | 1 | 通过 | 预算内重试 + 无锁确认 + 边界两句均出自 02 L55-71。 |
| Q24 | `None` | SUCCEEDED | 1 | groundedAnswer | 214/350 | 2 | 通过 | 基于 JChatMind 挂 03 L3-5，技术栈与五项保留能力挂 03 L9-18。 |
| Q25 | `None` | SUCCEEDED | 1 | groundedAnswer | 176/300 | 3 | 通过 | 旧协议（03 L63-66）与新协议（03 L70-77）分开说明，并补契约边界（03 L81-83）。 |
| Q26 | `None` | SUCCEEDED | 1 | groundedAnswer | 55/300 | 1 | 通过 | 资料不足走独立结果、不挂引用，出自 03 L70-77。 |
| Q27 | `None` | SUCCEEDED | 1 | groundedAnswer | 119/350 | 3 | 通过 | 来源可点开（03 L81-83）、引用≠正确（01 L27-29）、宁可标中断（02 L75-77）分别有据。 |
| Q28 | `None` | SUCCEEDED | 1 | groundedAnswer | 125/300 | 1 | 通过 | 去重键、同键返回、边界三句均出自 02 L8-22。 |
| Q29 | `None` | SUCCEEDED | 1 | groundedAnswer | 157/250 | 1 | 通过 | 非实时生成、历史记录、质量边界三句均出自 01 L40-42；因果连接在原文里就有。 |
| Q30 | `None` | SUCCEEDED | 1 | groundedAnswer | 78/300 | 1 | 通过 | 点开来源的做法与契约边界均出自 03 L81-83。 |

## 跨条件诊断（不作整组达标成绩）

本组只有一次运行，跨条件诊断与主组相同，不再另列。

**通过 30 / 30**；未通过：无。

## 合计对照

| 数据组 | 通过 | 用途 |
| --- | ---: | --- |
| 固定条件整组（shared 会话、30 题、题间 1 s、模型请求最小间隔 11 s、每题最多 3 次） | **30/30** | 正式整组结果 |
| 跨条件诊断（每题取已通过的一次） | **30/30** | 内容诊断，不作整组达标成绩 |

