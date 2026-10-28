# 逐题结果（ux08-2026-10-20）

运行条件：提示词 `docs\public-demo\v4-2026-10-28\agent-system-prompt-grounded.md`（sha256 `e2c639e59640d29a`）；语料 `v4-2026-10-28`；会话模式 `per-question`；消息窗口 `40`；最多尝试 3 次、退避 30.0 s。

| 题号 | 终态 | 首次尝试 | 尝试次数 | 交付通道 | 字数 | 达标 | 引用 | 陈述 | 未知编号 | 资料不足 | 后端 durationMs | 题级 elapsedMsTotal |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q03 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 353 | 否 | 3 | 3 | 0 | 否 | 18.77 | 20.27 |
| Q07 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 251 | 是 | 1 | 3 | 0 | 否 | 9.57 | 10.15 |
| Q08 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 176 | 是 | 1 | 3 | 0 | 否 | 8.65 | 10.17 |
| Q10 | SUCCEEDED | FAILED / EXECUTION_FAILED | 2 | groundedAnswer | 72 | 是 | 1 | 2 | 0 | 否 | 6.06 | 40.22 |
| Q13 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 53 | 是 | 0 | 0 | 0 | 是 | 6.91 | 8.11 |
| Q15 | SUCCEEDED | FAILED / EXECUTION_FAILED | 3 | groundedAnswer | 78 | 是 | 0 | 0 | 0 | 是 | 13.34 | 86.41 |
| Q16 | SUCCEEDED | FAILED / EXECUTION_FAILED | 3 | groundedAnswer | 138 | 是 | 2 | 3 | 0 | 否 | 70.90 | 141.08 |
| Q17 | SUCCEEDED | FAILED / INVALID_ANSWER_FORMAT | 3 | groundedAnswer | 115 | 是 | 2 | 3 | 0 | 否 | 6.95 | 88.42 |
| Q19 | SUCCEEDED | FAILED / EXECUTION_FAILED | 2 | groundedAnswer | 125 | 是 | 2 | 3 | 0 | 否 | 6.37 | 42.25 |
| Q21 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 52 | 是 | 1 | 1 | 0 | 否 | 4.26 | 6.14 |
| Q23 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 183 | 是 | 1 | 3 | 0 | 否 | 4.97 | 6.08 |
| Q25 | SUCCEEDED | FAILED / EXECUTION_FAILED | 2 | groundedAnswer | 290 | 是 | 3 | 3 | 0 | 否 | 5.36 | 38.20 |
| Q29 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 174 | 是 | 1 | 3 | 0 | 否 | 5.47 | 6.08 |
| Q30 | SUCCEEDED | SUCCEEDED | 1 | groundedAnswer | 183 | 是 | 2 | 3 | 0 | 否 | 7.97 | 8.11 |

## 汇总

- 题级：14 题，成功 14、失败 0；按状态 {'SUCCEEDED': 14}；按错误码 {}。
- 首次尝试成功 8 题；重试后转成功 6 题 ['Q10', 'Q15', 'Q16', 'Q17', 'Q19', 'Q25']。
- 尝试级：23 次（成功 14 / 失败 9，错误码 {'EXECUTION_FAILED': 8, 'INVALID_ANSWER_FORMAT': 1}）；运行 23 条；守卫拒绝 1 次。
- 模型调用：58 次（其中重试记 0 次），失败 8 次 {'MODEL_CALL_FAILED': 8}。
- 交付通道：{'groundedAnswer': 14}；grounded 陈述 33 条；资料不足结果 2 条。
- 陈述编号：按引用卡片直接对上 33 条，按正文编号还原 0 条，仍未对上 0 条。
- 达标 13 题 / 带引用 12 题 / 有未知编号 0 题 / 命中 mustNot 1 题（只统计成功题）。
- 后端 `durationMs`：p50 6.91 s / p95 18.77 s。
- 题级 `elapsedMsTotal`（含重试与退避）：p50 10.17 s / p95 88.42 s。
- 单次请求观察 `wallMs`（不含退避）：p50 8.12 s / p95 20.27 s。

> 三档耗时口径不同，不能互相顶替：`durationMs` 是后端运行自己的耗时，`wallMs` 只是一次请求的观察时间，`elapsedMsTotal` 才包含重试与退避。
