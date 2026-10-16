# 04 — 回答引用与原文定位

**优先级：第一阶段。前置：03 协议；可先用夹具开发 UI。消费者：05、08。**

## 可直接分发的提示词

> 请执行 ExoBrain 开发任务 04。先阅读 docs/development/README.md、docs/development/contracts.md 和 docs/development/tasks/04-verifiable-citations.md。为真实 KnowledgeTool 结果建立本次运行的来源登记，验证回答引用，并让用户点击引用打开现有 Markdown 预览、定位原文。兼容旧消息、游客/账号双模式与公开精选过滤。模型文本和客户端 metadata 不能伪造有效来源。不要重做整个聊天页或记录私有推理。交付后端、前端、浏览器/权限回归及 docs/development/deliveries/04.md；执行耗时追踪留给 05。

## 代码入口与所有权

- [JChatMind](../../../jchatmind/src/main/java/com/kama/jchatmind/agent/JChatMind.java)、[DirectAnswerTool](../../../jchatmind/src/main/java/com/kama/jchatmind/agent/tools/DirectAnswerTool.java)、[JChatMindFactory](../../../jchatmind/src/main/java/com/kama/jchatmind/agent/JChatMindFactory.java)。
- [ChatMessageDTO](../../../jchatmind/src/main/java/com/kama/jchatmind/model/dto/ChatMessageDTO.java)、消息转换/存储、[DemoCatalogService](../../../jchatmind/src/main/java/com/kama/jchatmind/demo/DemoCatalogService.java)。
- [AgentChatHistory](../../../ui/src/components/views/agentChatView/AgentChatHistory.tsx)、[DocumentPreviewDrawer](../../../ui/src/components/modals/DocumentPreviewDrawer.tsx)、[前端类型](../../../ui/src/types/index.ts)、[API 客户端](../../../ui/src/api/api.ts)。
- `RagService` 和检索工具输出由 03 负责。先冻结协议，不独自在此任务改检索 JSON。
- 运行时/聊天组件交付给 05 之前说明变更，避免并行覆盖。

## 后端实施

1. 本次运行创建自己的来源登记表，只接收实际完成且能匹配 toolCallId 的 KnowledgeTool 结果。不得通过扫描任意历史文本/用户 JSON 来登记来源。
2. 实现共享契约的 `AnswerCitation` 与消息 metadata 增量字段。可为答复工具增加可选 citationIds，同时兼容只有 answer 的调用和普通问候。
3. 来源 ID 必须属于本次运行且仍有访问权限；由服务端补全 KB、doc、chunk、行号、hash、片段及标签。无效 ID 不生成引用，不让模型提供任意文件路径或跳转地址。
4. 不伪造工具使用：普通答复、未执行检索、检索失败时可以显示正常答案/明确资料不足，但不能补出“已查询资料”的事件。
5. 不以提示词写死测试题、命中文本或固定工具轨迹。提示词只表达通用行为约束；工具权限和引用真实性由代码保障。
6. 历史消息继续可读。公开精选序列化对新 metadata 做白名单处理，核对来源发布状态；被撤下资料不能通过引用节选重新公开。
7. 一个有效的引用证明来源可追溯，不证明模型结论正确；不增加“已验证正确”等未经事实评测支持的状态。

## 前端体验

- 回答下方呈现紧凑来源列表/编号，显示文档名、章节和可读节选；需要展开时才展示技术细节。复用已有工具展开区，避免重复一大段 JSON 占据主阅读区。
- 引用点击调用现有授权文档原文 API，复用预览/源码切换。hash 匹配时定位并高亮源范围；渲染视图难以准确定位时直接提供可靠的源码行高亮，不用猜测的 DOM 位置充数。
- 连续点击两个来源要取消旧请求或检查响应 ID，迟到响应不能覆盖当前文档；关闭抽屉要清理相关请求和状态。
- 来源已改动、被删除、无权访问、暂时失败有可区分反馈；权限不足/不存在按原安全策略对外呈现，不展示隐藏文件名。
- legacy 来源只有文档 ID 时打开整份文档并说明没有精确位置；完全没有结构化来源的旧消息照常显示。
- 仅把服务端有效 citations 的编号变成交互，不把模型随手写的 `[1]` 或任意 Markdown 链接当可信来源。
- 保持 Markdown 现有 HTML/URL 防护，不能为了高亮而引入未净化的 HTML。键盘可聚焦/关闭，390px 移动端不横向溢出。
- 来源界面使用当前构建的 API 基地址，游客版和 `/admin/` 版都可工作。未登录不跳过认证去账号接口取内容。

## 验证矩阵

| 场景 | 预期 |
| --- | --- |
| 单/多次真实检索、重复命中同一章节 | 来源与真实 toolCall 对应，标签稳定且去重规则明确 |
| 模型编造、用户注入、其他会话来源 ID | 不生成有效引用，不读取目标私有文档 |
| 正文已更新或同名文档重新上传 | 不错误高亮旧位置；提示版本变化 |
| 删除知识库后打开旧对话 | 历史仍在，来源不可用，不恢复被删资源 |
| 管理员访问普通用户私有引用 | 与其他账号同样被拒绝 |
| 游客/普通用户/管理员访问公开来源 | 可读；普通用户和游客仍不能修改 |
| 撤下公开资料/精选 | 新字段不造成公开信息泄漏 |
| SSE 中断、刷新、快速切换来源、旧消息 | 无重复引用、错文档或空白页 |

后端用合成检索响应与真实权限流程测试，前端补有意义的引用行为测试，并用真实浏览器验证两种构建和移动端。截图/录屏只使用合成或可公开资料，不暴露登录凭据。

## 完成标准与交接

可从一个回答点击到确切原文，刷新后仍有效；非法来源无法访问；失效来源诚实降级。给 02 提供引用有效性/支持度可复核的样本，给 05 保留可选 runId 的扩展位置，给 08 提供 API/公开白名单变化和双构建验证结果。
