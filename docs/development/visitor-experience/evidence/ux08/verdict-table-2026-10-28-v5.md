# 逐题判定与分组合计（由 verdicts 数据生成，合计不手写）

判定规则：pass-criteria-2026-10-28.md（每题只计一次；串题/错引/无依据承诺/失败/超预算一律不通过）

数据来源：`verdicts-2026-10-28-v5.json`。生成前已校验：每题判定唯一、判定集合与该运行的题号集合完全一致、runId 非空、pass 为布尔值、非 SUCCEEDED 不判通过。

## 固定条件整组（shared 会话、30 题、题间 1 s、模型请求最小间隔 11 s、每题最多 3 次）

用途：正式整组结果。运行文件 `run-2026-10-28-grounded-30-v5.json`；提示词 sha256 `7d0cbbbfb920e652`；语料 `v5-2026-10-28`；会话模式 `shared`；消息窗口 `40`；题间间隔 1.0 s；最多尝试 3 次；退避 8.0 s。

运行事实：30 题（成功 30 / 失败 0）；尝试 30 次（成功 30 / 失败 0）；模型调用 62 次、失败 0 次；守卫拒绝 0 次；**首次尝试即成功 30 题**。

**内容判定通过 29 / 30**（首次尝试即成功 30 题；两者口径不同，不互相替代）。

| 题号 | runId | 终态 | 首次尝试 | 尝试 | 通道 | 字数/预算 | 引用 | 判定 | 理由 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q01 | `686a1329-2680-4a7f-8434-a9ea6bba20d9` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 130/350 | 2 | 通过 | 一句话定位挂 01 L9-15，三要点同段，三条体验路径挂 01 L19-23；130 字达标。 |
| Q02 | `75f9ec0b-2736-44b6-b97f-68d626a16c90` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 211/350 | 2 | 通过 | 定位粒度挂 03 L81-83，新协议的短引校验挂 03 L70-77，契约边界挂 L81-83；211 字达标。 |
| Q03 | `95dd6a6c-e9d8-4a19-b660-2716e4f807d4` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 200/350 | 3 | 通过 | 只列继承（Agent 主循环，03 L9-18）与新增的一项（可核验引用，03 L22-31）并标明「之一」，不再越界列举；200/350。 |
| Q04 | `f055b761-1859-4068-9be3-b7f4756b01d5` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 176/350 | 2 | 通过 | 可信任依据挂 01 L9-15（含运行记录），边界挂 01 L27-29；176/350 达标。 |
| Q05 | `a518d0d5-1693-4a1a-9a80-9ccc074c340d` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 99/300 | 1 | 通过 | 能解决 / 不能解决两点同出自 01 L27-29；99 字。 |
| Q06 | `87e63301-9222-4bcc-a7a7-ba5d09719b8e` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 154/350 | 1 | 通过 | 去重键、同键返回 runId、边界三句均出自 02 L8-22，未承诺外部 exactly-once。 |
| Q07 | `529ee994-1c59-4deb-bd8f-11f9847f7a35` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 230/350 | 1 | 通过 | 取消三步与边界（不撤回已发请求、不承诺立即停止）均出自 02 L26-42。 |
| Q08 | `2392ff7d-5670-46f0-9332-e649d46cdaad` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 176/350 | 1 | 通过 | QUEUED 重启重领、RUNNING 记 INTERRUPTED、恢复范围三句均出自 02 L46-51。 |
| Q09 | `492adfd3-cdbe-4aa3-893e-c4e1075962a9` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 175/350 | 1 | 通过 | 共用线程的危害、独立调度器方案、真实 PostgreSQL 用例均出自 02 L55-71。 |
| Q10 | `98f943f5-c1a0-4eab-a85a-adbce0eeb968` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 75/300 | 1 | 通过 | 明确「不是」，两句均出自 03 L44-48。 |
| Q11 | `8154aca2-91b9-4363-9a63-bff1c5c0abaf` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 79/300 | 2 | 通过 | 引用≠结论正确，两句分别挂 03 L81-83 与 01 L27-29，无新增因果。 |
| Q12 | `06fe2591-3887-4b76-9ef6-c0a16e594b4d` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 81/300 | 2 | 通过 | 管理员不可读他人资源挂 01 L33-36，资源隔离挂 03 L31-33。 |
| Q13 | `adecfeca-1d27-4be3-9c88-b00dd5f7982c` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 57/300 | 0 | 通过 | 合格拒答：明确说没有客户数或成本数据，零引用。mustNot 的「节省」出现在否认句里，属正则误伤。 |
| Q14 | `8240956f-745b-4112-ad13-b3786b74ca36` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 50/300 | 0 | 通过 | 合格拒答：明确说没有作者履历，零引用。 |
| Q15 | `186f8deb-4d75-48bf-8cdc-a91fdc3503f5` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 49/300 | 0 | 通过 | 合格拒答：明确说没有文件格式清单，零引用。 |
| Q16 | `683af88f-359e-400d-81a7-7c34b385f578` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 86/300 | 2 | 通过 | 定位粒度直接出自 03 L81-83；短引校验出自 03 L70-77，属同一主题的协议约束。 |
| Q17 | `e687ef97-68c6-481e-bc00-29c5636dc5ee` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 38/300 | 1 | 通过 | 只答导入失败的处理：入队、失败重试、固定错误码、状态可查询，全部出自 03 L22-31；换向量模型那句无关内容已消失（79→38 字）。 |
| Q18 | `4901f228-c829-4365-b8e3-eac40a7d6e10` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 108/300 | 2 | 通过 | 查看过程可见项挂 01 L19-23，运行记录挂 01 L9-15。 |
| Q19 | `3137983e-bd25-4b88-b97c-5ce8b0f537fe` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 124/300 | 1 | 通过 | 游客范围、公开资料发布边界、账号隔离三句均出自 01 L33-36。 |
| Q20 | `7436d855-1504-4ea6-8bef-9c068850c237` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 58/300 | 1 | 通过 | 三项指标与配套资产均出自 03 L22-31。 |
| Q21 | `5a7d4726-d239-4a71-b258-8875f23300f5` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 68/300 | 2 | 通过 | 不能直接换（03 L44-48）与换绑守卫（03 L31-33）分挂，无串题。 |
| Q22 | `3d40c389-128b-4f33-a3e9-4ec99a436026` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 189/350 | 2 | **不通过** | 第 3 条把「多实例部署与网络分区尚未验证」泛化成 fencing 本身「属于已实现而非已验证」：所引 03 L37-40 限定的是跨主机、时钟漂移、网络分区这些场景，而同一答案引用的 02 L37-39 已记载 PostgreSQL 下写回、租约接管与取消的验证。正确表述应是「单机相关路径已验证，跨主机等场景未验证」；把范围限定泛化成「未验证」属于超出原文的结论，按不通过记录。 |
| Q23 | `fb21cbc9-c145-4649-a0bb-f1c02a95d146` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 139/350 | 1 | 通过 | 预算内重试 + 无锁确认 + 边界两句均出自 02 L55-71。 |
| Q24 | `6f0e6be7-7c02-4252-b487-8a1a4682b462` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 214/350 | 2 | 通过 | 基于 JChatMind 挂 03 L3-5，技术栈与五项保留能力挂 03 L9-18。 |
| Q25 | `4824eab6-c843-4297-960b-2cb058447f87` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 176/300 | 3 | 通过 | 旧协议（03 L63-66）与新协议（03 L70-77）分开说明，并补契约边界（03 L81-83）。 |
| Q26 | `2d771b58-2926-4e65-bccc-2f7da92e94e5` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 55/300 | 1 | 通过 | 资料不足走独立结果、不挂引用，出自 03 L70-77。 |
| Q27 | `1584ebdf-618b-45b2-8971-35c8a1e0d266` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 119/350 | 3 | 通过 | 来源可点开（03 L81-83）、引用≠正确（01 L27-29）、宁可标中断（02 L75-77）分别有据。 |
| Q28 | `3e26feb7-e4d5-472a-ace5-7d1e1553ef92` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 125/300 | 1 | 通过 | 去重键、同键返回、边界三句均出自 02 L8-22。 |
| Q29 | `9204d29f-8e92-4d80-9cc0-b7ea146ee7b6` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 157/250 | 1 | 通过 | 非实时生成、历史记录、质量边界三句均出自 01 L40-42；因果连接在原文里就有。 |
| Q30 | `b4c02480-8abd-4293-9abd-9205f6c3b22b` | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 78/300 | 1 | 通过 | 点开来源的做法与契约边界均出自 03 L81-83。 |

## 跨条件诊断（不作整组达标成绩）

本组只有一次运行，跨条件诊断与主组相同，不再另列。

**通过 29 / 30**；未通过：Q22。

## 合计对照

| 数据组 | 内容通过 | 用途 |
| --- | ---: | --- |
| 固定条件整组（shared 会话、30 题、题间 1 s、模型请求最小间隔 11 s、每题最多 3 次） | **29/30** | 正式整组结果 |
| 跨条件诊断（每题取已通过的一次） | **29/30** | 内容诊断，不作整组达标成绩 |

