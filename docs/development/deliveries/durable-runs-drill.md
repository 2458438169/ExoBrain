# 07 多实例 / 并发 / 真实进程崩溃演练（2026-10-18）

被测对象：提交 `a6bc0ae` 构建的 `jchatmind-0.0.1-SNAPSHOT.jar`，**两个独立 JVM 进程**（实例 A `127.0.0.1:18090`、
实例 B `127.0.0.1:18091`）连同一个 PostgreSQL 库（一次性库 `exobrain_eval02`），真实 Embedding 与真实对话模型
（`DeepSeek-V4-Flash-0731`）。

- 队列参数：`app.runs.lease-seconds=20`、`poll-interval-ms=1000`、`max-concurrent-runs=2`、`max-active-per-actor=5`。
  把租约压到 20 s 是为了在分钟级演练里观察到过期与中断；默认值是 120 s（第 5 节的额外冒烟用默认值复测）。
- 夹具：账号 `eval02`、助手 `eval assistant`（`3acc45f6-…`）、知识库 `f2e4c9b7-…`（`body-v1`，23 chunk）。
- worker 身份：`host/pid@host/random8`，例如 `QuantumCopter/48244@QuantumCopter/1cf68744`，落库在
  `exobrain_chat_run_job.lease_owner`（进入终态后该列被清空，所以必须**运行中**采样）。

## 1. 结果

| 场景 | 操作 | 结论 | 证据 |
| --- | --- | --- | --- |
| 同会话并发提交 | 同一会话、两个不同幂等键，分别发到 A 与 B | **通过** | A 返回 200 并给出 `runId=9d47f954-…`，B 返回 **409**；`exobrain_run` 与 `exobrain_chat_run_job` 各 **1** 行；运行被 A 领取（`QuantumCopter/48244@…`）并 `SUCCEEDED`；会话消息 `user=1`，助手 1 条正文 + 占位消息，工具消息 2 条来自**同一次运行里模型两次 KnowledgeTool 调用**（两个不同 `retrievalId`），不是重复提交 |
| 幂等键重试 | 同一个 key 依次打 A、B、A | **通过** | 三次都 200，`runId` 全部为 `deaefc27-…`，第 2、3 次 `idempotentReplay=true`；`exobrain_chat_run_job` 只有 1 行且 `attempts=1`；`exobrain_run` 1 行 `SUCCEEDED`；消息 `user=1 / assistant=2（占位+正文）/ tool=2` |
| 双实例都执行 | A、B 各接一个会话 | **通过** | A 领取 `9d47f954-…`（owner `QuantumCopter/48244@…`），B 领取 `37e6e920-…`（owner `QuantumCopter/45092@…`），两者都 `SUCCEEDED`；说明不是“只有一个实例在干活” |
| 真实进程崩溃 | 运行中 `Stop-Process -Force`（等价 SIGKILL）杀掉持锁的 A（pid 48244） | **按设计通过** | 运行 `e4d2826a-…` 被杀时处于 RUNNING（owner=A）；**22.1 s** 后由 B 标记 `INTERRUPTED`，`error_code=INTERRUPTED_BY_RESTART`、`trace_incomplete=false`；该会话只有 `user=1`，**没有半截助手消息**；job `INTERRUPTED / attempts=1`、`lease_owner` 已清空；**没有自动重放**（与 07 的契约一致：不承诺任意点续跑，只用终态识别中断） |
| 崩溃后仍可用 | 换新会话在存活的 B 上再提交 | **通过（默认租约下）** | 见第 5 节：默认 `lease-seconds=120` 下连跑 3 个问题，3/3 `SUCCEEDED` |

## 2. 演练中发现的真实限制（20 s 租约低于模型时延）

把租约压到 20 s 后出现一次**存活实例自己的运行被判中断**：

- 运行 `9b45cb82-…` 于 `11:40:02` 被 B（`QuantumCopter/45092@…`）领取，`11:40:22` 被 B 自己的过期扫描标记
  `INTERRUPTED`；`exobrain_run_event` 该运行 **0 行**，会话里只有用户消息。
- 日志同一实例内可见：`Run lease expired; the run was marked INTERRUPTED and will not be replayed: run=9b45cb82-… attempts=1`。
- 原因：租约只在检查点（模型/工具步骤之间）续租；当**单次模型调用 + 检索超过租约**时，扫描线程会把仍存活的
  运行当作过期。默认 120 s 远大于本次实测的 7–20 s 单轮时延，因此默认配置不受影响；但换成更慢的模型或更长
  上下文时，这个组合会重现。
- 集成测试里「有活租约的任务不会被其它实例中断」用的是**另外的 repository 实例**，覆盖不到“自己扫描自己”的
  这条路径；这是本次真实演练补上的信息。

**建议（未改代码）**：给运行加定时心跳续租（独立于步骤），或让过期判定只处理 `lease_expires_at + 宽限` 之外、
且 fencing 未被推进的任务；同时在运维文档里写明 `lease-seconds` 必须大于模型单次调用的 p99。

## 3. 与 07 交付记录的关系

`docs/development/deliveries/07.md` 第 6 节原先把「多实例」「并发 HTTP 提交」「真实进程重启」列为未验证，本节即补做：
三项均已执行，结论如上；其中“同会话并发”“幂等键重放”“崩溃后不重放”符合设计，另外记录了第 2 节的租约时延限制。

## 4. 未执行 / 限制

- 多**主机**部署（本次两个实例在同一台机器、同一网段、共用系统时钟）：**未执行**。
- 网络分区、时钟漂移、数据库主备切换：**未执行**。
- 三个及以上实例的争抢、`max-concurrent-runs` 打满时的拒绝路径：**未执行**。
- 导入队列（06）的多实例并发：**未执行**（本次只演练对话运行队列；导入队列的故障注入由 06 的 14 项集成测试覆盖）。
- Linux 上的 `kill -9` 与容器 `docker kill`：**未执行**（Windows 上用 `Stop-Process -Force` 终止 JVM，语义等价）。

## 5. 默认配置下的冒烟（补充）

把租约恢复为默认（`app.runs.lease-seconds` 不设置 = 120 s）后重启 A/B，在 B 上连跑 3 个问题：

| 序号 | 提交 | 运行 | 结果 |
| --- | --- | --- | --- |
| 1 | 200 | `d86a03d5-…` | SUCCEEDED |
| 2 | 200 | `90d4dcb3-…` | SUCCEEDED |
| 3 | 200 | `bc1d6080-…` | SUCCEEDED |

## 6. 复现要点

1. 同一份外部配置（一次性库 `exobrain_eval02`），两个进程只用 `--server.port` 区分，另加
   `--app.runs.lease-seconds=20 --app.runs.poll-interval-ms=1000 --app.runs.max-active-per-actor=5`。
2. 登录一次拿 cookie + CSRF；两个实例共用同一个会话（会话存在数据库里）。
3. 同会话并发：`POST /api/chat-messages` 带不同 `idempotencyKey`，同一 `sessionId`，一个发 A 一个发 B。
4. 幂等：同一 `idempotencyKey` 重复提交，比较 `runId` 与 `idempotentReplay`。
5. 崩溃：轮询 `exobrain_chat_run_job` 到 `status=RUNNING` 后按 `lease_owner` 里的 pid 杀进程，再观察
   `exobrain_run.status` 与 `error_code`；`lease_owner` 在终态会被清空，必须运行中采样。
