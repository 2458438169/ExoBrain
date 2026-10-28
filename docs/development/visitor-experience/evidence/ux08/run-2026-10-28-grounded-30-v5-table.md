# 逐题结果（ux08-2026-10-20）

运行条件：提示词 `docs\public-demo\v5-2026-10-28\agent-system-prompt-grounded.md`（sha256 `7d0cbbbfb920e652`）；语料 `v5-2026-10-28`；会话模式 `shared`；消息窗口 `40`；最多尝试 3 次、退避 8.0 s。

| 题号 | 终态 | 首次尝试 | 尝试次数 | 交付通道 | 字数 | 达标 | 引用 | 陈述 | 未知编号 | 资料不足 | 后端 durationMs | 题级 elapsedMsTotal |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q01 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 130 | 是 | 2 | 3 | 0 | 否 | 16.87 | 18.27 |
| Q02 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 211 | 是 | 2 | 3 | 0 | 否 | 29.40 | 30.42 |
| Q03 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 200 | 是 | 3 | 3 | 0 | 否 | 21.32 | 22.38 |
| Q04 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 176 | 是 | 2 | 3 | 0 | 否 | 16.30 | 18.30 |
| Q05 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 99 | 是 | 1 | 2 | 0 | 否 | 18.92 | 20.27 |
| Q06 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 154 | 是 | 1 | 3 | 0 | 否 | 20.50 | 22.33 |
| Q07 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 230 | 是 | 1 | 3 | 0 | 否 | 19.72 | 20.30 |
| Q08 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 176 | 是 | 1 | 3 | 0 | 否 | 19.77 | 20.33 |
| Q09 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 175 | 是 | 1 | 3 | 0 | 否 | 20.07 | 20.26 |
| Q10 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 75 | 是 | 1 | 2 | 0 | 否 | 20.25 | 22.30 |
| Q11 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 79 | 是 | 2 | 2 | 0 | 否 | 19.50 | 20.31 |
| Q12 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 81 | 是 | 2 | 2 | 0 | 否 | 19.99 | 20.34 |
| Q13 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 57 | 是 | 0 | 0 | 0 | 是 | 20.18 | 22.28 |
| Q14 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 50 | 是 | 0 | 0 | 0 | 是 | 19.03 | 20.30 |
| Q15 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 49 | 是 | 0 | 0 | 0 | 是 | 20.33 | 22.34 |
| Q16 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 86 | 是 | 2 | 2 | 0 | 否 | 19.66 | 20.27 |
| Q17 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 38 | 是 | 1 | 1 | 0 | 否 | 20.23 | 22.31 |
| Q18 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 108 | 是 | 2 | 2 | 0 | 否 | 17.65 | 18.36 |
| Q19 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 124 | 是 | 1 | 3 | 0 | 否 | 20.33 | 22.28 |
| Q20 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 58 | 是 | 1 | 1 | 0 | 否 | 18.91 | 20.33 |
| Q21 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 68 | 是 | 2 | 2 | 0 | 否 | 19.43 | 20.30 |
| Q22 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 189 | 是 | 2 | 3 | 0 | 否 | 34.24 | 34.42 |
| Q23 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 139 | 是 | 1 | 2 | 0 | 否 | 17.99 | 18.26 |
| Q24 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 214 | 是 | 2 | 3 | 0 | 否 | 21.67 | 22.27 |
| Q25 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 176 | 是 | 3 | 3 | 0 | 否 | 19.22 | 20.28 |
| Q26 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 55 | 是 | 1 | 1 | 0 | 否 | 20.20 | 22.33 |
| Q27 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 119 | 是 | 3 | 3 | 0 | 否 | 19.06 | 20.38 |
| Q28 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 125 | 是 | 1 | 3 | 0 | 否 | 19.08 | 20.30 |
| Q29 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 157 | 是 | 1 | 3 | 0 | 否 | 19.60 | 20.26 |
| Q30 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 78 | 是 | 1 | 2 | 0 | 否 | 20.41 | 22.38 |

## 汇总

- 题级：30 题，成功 30、失败 0；按状态 {'SUCCEEDED': 30}；按错误码 {}。
- 首次尝试成功 30 题；重试后转成功 0 题 。
- 尝试级：30 次（成功 30 / 失败 0，错误码 {}）；运行 30 条；守卫拒绝 0 次。
- 模型调用：62 次（其中重试记 0 次），失败 0 次 {}。
- 交付通道：{'groundedAnswer': 30}；grounded 陈述 66 条；资料不足结果 3 条。
- 陈述编号：按引用卡片直接对上 66 条，按正文编号还原 0 条，仍未对上 0 条。
- 达标 30 题 / 带引用 27 题 / 有未知编号 0 题 / 命中 mustNot 1 题（只统计成功题）。
- 后端 `durationMs`：p50 19.72 s / p95 29.40 s。
- 题级 `elapsedMsTotal`（含重试与退避）：p50 20.33 s / p95 30.42 s。
- 单次请求观察 `wallMs`（不含退避）：p50 20.33 s / p95 30.42 s。

> 三档耗时口径不同，不能互相顶替：`durationMs` 是后端运行自己的耗时，`wallMs` 只是一次请求的观察时间，`elapsedMsTotal` 才包含重试与退避。
