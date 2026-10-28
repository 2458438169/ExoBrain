# 429 分诊与节奏（2026-10-28）

目标：把「45 次模型失败都是 429」进一步拆到**可诊断**，并据此安排节奏；不是把无限重试当可用率修复。

## 1. 现在能记到什么（代码侧）

`ModelRuntimeService.SAFE_ERRORS` 之前只写状态码，其余全靠猜。本轮改为**白名单诊断**：

| 字段 | 来源 | 缺失时 |
| --- | --- | --- |
| `http` | 供应商返回的状态码 | 一定有 |
| `retry-after` | `Retry-After` 头，仅接受纯数字秒 | `unknown` |
| `code` | 响应体里的 `error.code` / `error.type` / `code`，只接受 `[A-Za-z0-9_.:-]{1,64}` | `unknown` |
| `req` | `x-request-id` / `request-id` / `x-amzn-requestid` / `cf-ray`，只接受 `[A-Za-z0-9_.:-]{1,32}` | `unknown` |

落点：`ProviderFailure`（解析 + 生成 detail）、`ProviderUnavailableException`（把诊断带出异常链）、
`ModelRuntimeService`（错误处理器与 `SafeChatModel`）、`RunRecorder.modelCall(..., detail)`。

写进模型调用事件 `detail` 的形状：

```
call=3;retrieval=true;http=429;retry-after=unknown;code=unknown;req=unknown
```

- `call=n`：本次运行内第 n 次真实模型请求（跨步累计），用来区分**首个调用**与**检索后的后续调用**；
- `retrieval=true|false`：本次请求之前是否已经发生过知识库检索。

不写完整响应头、不写响应体全文、不写 URL 与凭据。

## 2. 实测：这个中转站的 429 长什么样

探针：`scripts` 之外的一次性脚本（只发 `max_tokens=8` 的最小请求），证据
`model-429-probes-2026-10-28.json`。

**响应特征**：429 响应**没有 `Retry-After`、没有 `x-ratelimit-*`、没有错误体**
（`errorCode=null`、`errorMessage=""`）。因此「是 RPM、TPM 还是余额/配额」**无法从响应里区分**，
代码只能如实记 `unknown`——这一点之前只能靠猜，现在有证据。

| 组 | 请求间隔（实测含约 6 s 服务耗时） | 结果 |
| --- | --- | --- |
| burst | 首请求后 0.5 s 间隔（有效约 6.5 s） | 前 6 次 200，第 7 次 429，第 8 次 200，第 9–10 次 429 |
| spacing | 1 s 间隔（有效约 7 s） | 前 6 次 200，第 7 次 429，第 8 次传输错误，其后 11 s 间隔全部 200 |
| paced | 10 s 间隔（有效约 16 s） | 9 次连续 200，0 次 429 |

结论（限定在本日、本账号、该中转站）：

- 触发条件是**短窗口内的请求数**，边界出现在「同一 ~40 s 窗口内的第 7 次请求」；
- 有效间隔 ≥ 约 11 s 时未再复现 429（9 次连续调用）；
- 每次 429 之后不久又能成功，说明这不是硬封禁，也不是余额耗尽的表现；
- 具体是 RPM 还是别的名额无法从响应判定，标为 `unknown`。

## 3. 节奏与有界退避（代码侧）

1. `ModelRequestPacer`：按 `app.models.min-interval-ms`（环境变量 `APP_MODELS_MIN_INTERVAL_MS`）
   在**每次真实模型请求**之间节流，默认 0（默认行为不变）。选请求层而不是题间 sleep，
   因为一题本身就要 2–3 次模型调用，题间 sleep 覆盖不到一题内的连续请求。
   本轮实验取 **11000 ms**。
2. `JChatMind.callModelWithRetry`：429/限流类失败按 `Retry-After`（有就用）否则指数退避
   （2 s、4 s，上限 30 s）重试，**最多 3 次**；超过即真实失败。非限流失败不重试。

## 4. 效果（同一天的对照）

| 运行 | 模型调用 | 模型失败 | 429 日志行 | 运行结果 |
| --- | --- | --- | --- | --- |
| `736fa1b` 主运行（无节流） | 85 | 37 | 37 | 16 成功 / 14 失败 |
| `736fa1b` 补跑（无节流，题间 5 s） | 58 | 8 | 8 | 14 成功 / 0 失败 |
| 本轮固定条件整组（11 s 请求间隔） | 62 | **0** | **0** | **30 成功 / 0 失败** |

注意：这组对照同时改了节流与提示词，**不能把可用的改善单独归因于节流**；能说的是
「在 11 s 请求间隔下，本轮 62 次模型调用没有出现 429」。
