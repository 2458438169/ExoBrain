# ADR 0004 — 可核验回答引用

- 状态：已实现（任务 04）
- 日期：2026-10-17
- 相关：[共享契约](../development/contracts.md) 第 2、4 节，[任务 04](../development/tasks/04-verifiable-citations.md)，ADR 0003（正文索引与来源位置）

## 问题

回答要让用户能核对来源：点开、看到原文、确认位置。难点不在渲染，而在**谁来定义来源**。如果让模型输出文件名、行号或 URL，模型可以凭空生成一个看起来合理但不存在的引用；如果靠扫描工具调用文本来登记来源，同样会把用户输入或旧历史里的字符串当成来源。

## 考虑过的方案

1. **模型在答复里写 Markdown 链接或 `[1]` 标记**：实现最省事，但引用身份完全由模型生成，无法验证；用户看到的链接与真实检索结果无必然关系。否决。
2. **从对话历史/工具调用文本里正则匹配来源**：能覆盖“模型提到了文档名”的情况，但把不可信文本当作来源登记，也解释不清“这条引用来自哪一次检索”。否决。
3. **服务端登记 + 模型只能选择**：本次运行维护一份来源登记表，条目只来自真实完成且能按 `toolCallId` 匹配的 `KnowledgeTool` 结果；模型只能挑 `citationId`，其余字段由服务端补全。**采纳**。
4. **把整个检索对象透传给前端**：会让内部字段（chunk 正文、索引内部标识）进入公开精选与历史序列化。否决。

## 最终选择

- `CitationRegistry`（`com.kama.jchatmind.citation`）按**每次运行**创建：`JChatMind` 每次运行都是新实例，登记表天然按运行隔离，不会跨会话或跨用户复用。
- 登记入口 `registerCompletedCalls(completed, requestedToolCallIds)` 同时要求：`toolCallId` 出现在本轮 assistant 消息**实际请求**的工具调用里、工具名是 `KnowledgeTool`、返回数据能解析成契约 §2 的 `RetrievalEnvelope` 且 `status = OK`。其余一律忽略。
- `DirectAnswerTool` 增加可选 `citationIds`；只有 `answer` 的旧调用照常工作。`citationIds` 只表达“模型想引用哪几条”，形状无效时整段忽略。
- 校验与补全 `resolve(requestedCitationIds, isSourceAuthorized)`：先 `runtimeGuard.run()` 重新确认身份/会话仍有效，再要求来源 KB 仍在本次运行的获准集合内，然后由服务端补全 `label / source / indexVersion / indexRevision / excerpt`。未知、跨运行、跨用户、未授权、重复来源、超限都只记录安全原因码与数量，不生成可点击引用。
- 去重键是“同一 KB + 同一文档 + 同一来源范围”（有行号用 `sourceHash@start-end`，legacy 无位置时退化为 `chunkId`），因此同一章节被多次命中只出现一次，不同章节不会被合并。
- 引用写进消息 metadata 的 `citations` 字段，仅在**确实产出面向用户答复**时非空；普通问候、工具失败、`NO_MATCH` 不产生引用，也不伪造工具事件。
- `DemoCatalogService` 对公开精选做字段白名单：只暴露展示需要的字段，且来源必须仍在公开名单内；被撤下的资料不会通过引用节选重新公开。

## 代价

- 模型必须配合选择 `citationId`：不选就没有引用（诚实降级，而不是补一个猜测）。
- 每次运行保留一份内存登记表（上限 10 条引用），运行结束即释放；没有跨运行的引用复用。
- 引用保存在消息 JSON 里，随文档更新会变成“位置不可核验”，需要界面按失效状态显示；这比静默指向错误行号更好。

## 验证

- 后端：`CitationRegistryTest`、`JChatMindCitationTest`（脚本化模型响应回放真实执行循环）、`DirectAnswerToolTest`、`ChatMessageConverterCitationTest`、`DemoCatalogServiceCitationTest`；整仓默认 profile 230 通过 / 0 失败 / 4 跳过（既有 Windows 符号链接跳过），真实 PostgreSQL 集成 19/19 通过。
- 前端：`npm run lint` 与游客版、账号版两次构建通过。

## 何时重新评估

- 若引入跨运行的来源引用（例如“上次回答引用过的资料”），需要重新设计登记的授权与生命周期。
- 若来源位置改为语义锚点（片段指纹而非行号），去重键与失效判定需同步修订。
- 若公开精选需要展示引用，应在白名单扩展到新字段时重新核对发布状态过滤。
