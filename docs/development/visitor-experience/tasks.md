# 实施任务书

所有任务遵守 [共享契约](contracts.md)。交付到本目录下新建的 `deliveries/UX-xx.md`，记录实际提交与证据；不要将下面的完成条件直接复制成已通过结论。

<a id="ux-01"></a>
## UX-01：公开运行接口与关联信息

**目标：** 游客能读取自己运行的状态与过程，能取消自己的活动运行；刷新后无答案的运行仍能对应原问题。

### 代码入口

- [网关模板](../../../deploy/nginx.conf)
- [DemoRunController](../../../jchatmind/src/main/java/com/kama/jchatmind/execution/DemoRunController.java)、[DemoRunService](../../../jchatmind/src/main/java/com/kama/jchatmind/execution/DemoRunService.java)、[RunViews](../../../jchatmind/src/main/java/com/kama/jchatmind/execution/RunViews.java)
- [RunControlController](../../../jchatmind/src/main/java/com/kama/jchatmind/runs/RunControlController.java)、[RunControlService](../../../jchatmind/src/main/java/com/kama/jchatmind/runs/RunControlService.java)
- [DemoRunRoutingTest](../../../jchatmind/src/test/java/com/kama/jchatmind/execution/DemoRunRoutingTest.java)
- 线上有效网关路径与差异见 [基线](evidence/baseline.md)。

### 实施步骤

1. 记录候选提交、仓库网关与线上有效网关的差异，确认请求实际穿过的 Nginx 配置。仓库模板已有部分规则，不能以「源码里已存在」关闭这个任务。
2. 为共享契约列出的运行列表、运行详情、导入状态和取消路径补齐精确规则。取消为 POST；GET 不得触发取消。沿用现有身份、所有权、同源校验和路径规范化规则。
3. 将生产所需的差异形成可重复应用的补丁或发布脚本。保留生产配置中的既有转发、认证与限流设置，避免整份覆盖造成无关变化。
4. 游客自身运行响应补充 `triggeringMessageId`、`answerMessageId` 这两个可空关联字段。只能在确认当前访客拥有该会话后返回；公开示例继续使用受限投影。关联必须来自数据库，不能靠文本或时间排序猜测。具体契约见 [运行关联](contracts.md#run-association)。
5. 向前端负责人交付成功、空列表、失败/无答案、取消中、终态的响应样本。不修改前端的错误态实现；它归 UX-04。
6. 在测试网关后面跑真实 HTTP 请求，再在最终发布时复核线上路径。控制器单测通过不能替代 Nginx 验证。

### 验收

- 自己的运行列表和详情为 200 JSON，解包后内容与真实运行对应；不存在或不属于自己的资源仍按现有授权语义拒绝。
- 第二个游客无法读取或取消第一个游客的运行；登录账号的运行不通过游客路径泄露。
- 自己的无答案运行有可靠的触发消息关联；公开示例不因此获得账号内部投影、配置哈希或模型目录等字段。
- 取消 QUEUED / RUNNING / 已终态三类运行均有明确响应；运行取消请求与最终取消状态保持区别。
- 未发布路径、非法路径编码、公开导入重试等接口仍被限制；现有聊天与 SSE 正常。
- 一次网关配置验证、一个有效请求和一个拒绝请求的证据均保留。运行中断/取消的持久语义沿用已有测试。

### 交付

接口/配置提交、部署差异、脱敏响应样本、网关验证脚本、测试摘要。向 UX-03/04 明确关联字段何时存在；向 UX-06 提供可检查的规则与版本。

<a id="ux-02"></a>
## UX-02：新版演示资料、正文索引与真实示例

**目标：** 新问题检索到最新的项目事实，引用有位置，个人贡献有依据，旧评测和旧会话可复核。

### 代码与资料入口

- [README](../../../README.md)、[架构](../../architecture.md)、[能力归属](../../provenance.md)
- [旧演示资料](../../public-demo/01_exobrain_overview.md)、[原提示词](../../public-demo/agent-system-prompt.md)
- [冻结资产测试](../../../evaluation/tests/test_frozen_assets.py)、[冻结语料清单](../../../evaluation/corpus/v1-2026-10-14/manifest.json)
- [DocumentController](../../../jchatmind/src/main/java/com/kama/jchatmind/controller/DocumentController.java)、[IngestionJobRunner](../../../jchatmind/src/main/java/com/kama/jchatmind/ingestion/IngestionJobRunner.java)
- [IndexPublisher](../../../jchatmind/src/main/java/com/kama/jchatmind/retrieval/IndexPublisher.java)、[DemoProperties](../../../jchatmind/src/main/java/com/kama/jchatmind/demo/DemoProperties.java)

### 内容设计

新建版本目录 `docs/public-demo/v2-2026-10-19/`，推荐三份短文档：

| 建议文件 | 内容职责 |
| --- | --- |
| `01_product_guide.md` | 项目能解决什么问题、三条体验路径、可核验的功能与边界 |
| `02_engineering_cases.md` | 重复提交、取消与迟到答复、行锁和租约三类案例；用问题、方案、验证结果组织 |
| `03_contribution_map.md` | 上游继承能力、本仓库新增能力、对应源码/设计说明与验证范围 |

每个小节围绕一个可直接提问的问题，使用短段落，核对日期与依据明确。案例中的耗时、测试数量只能来自真实证据；区分代码已实现、已部署和已验证。

同目录可放 `agent-system-prompt.md` 与 `manifest.json`。只有 manifest 明确列出的正文文档可上传；提示词、测试问题、验收报告、答案标注和本方案不进入知识库。

### 实施步骤

1. 保持根目录原有三份旧 `.md`、`evaluation/corpus/v1-2026-10-14/**` 与冻结题集不变。新增版本资料，在 `docs/public-demo/README.md` 说明当前版本与历史版本，主 README 改为链接当前版本。
2. 为新增目录补充 LF 规则，并记录每份文档的原始字节哈希；不能通过删除冻结断言或重新生成旧 freeze 文件来绕过版本约束。
3. 更新 README、架构和能力归属中的过时说明。当前已实现的正文索引、引用、执行追踪和持久任务应说明清楚；仍未实现的能力继续明确其边界。
4. 明确提示词策略：默认回答一句结论和至多三个要点；需要资料的结论用真实检索支撑；不确定时简短说明资料不足；用户明确要求细节时允许展开。正确答案不必每次重复资料日期、授权说明和长篇限定语。
5. 在隔离环境创建新版候选知识库，通过产品上传与导入队列生成正文索引。逐项检查任务成功、active revision 为已发布版本、文档数与哈希一致、来源行号可靠。
6. 绑定当前部署中适用的同一个 Embedding 模型和向量空间；记录绑定与维度。不要将旧向量和新供应商生成的向量混在同一个索引中。
7. 使用产品检索和真实模型运行验证三个推荐问题，再补资料不足、同义改写和贡献归属问题。保存回答、实际引用与人工核对结果。
8. 为线上准备新的知识库和索引发布清单。保持现有公开 Agent 的 ID，验证后更新它的提示词与 `allowedKbs`；现有会话与公开示例按该 Agent ID 关联，随意换 ID 可能使它们不可见。
9. 更新 `app.demo.public-kb-ids` 与所需的示例配置，确认配置实际生效方式。旧公开资料如仍被历史会话或旧示例引用，应继续可读；新的检索范围只使用已验证的新库。
10. 新建并真实执行三条专用于展示的示例，经检查后加入发布清单。标明它们是历史执行的只读示例；「亲自试一试」创建个人运行，不冒充实时结果。

### 数据发布清单

至少包含：资料版本、代码 SHA、文件/哈希、旧与新 KB ID、实际 Embedding 绑定、active revision、文档 ID、公开 Agent ID、提示词哈希、allowedKbs、公开范围与示例 ID 的预期变化。清单不得包含 API Key、Cookie 或私密配置正文。

候选索引准备、验证和最终切换要能分开执行。若当前管理 API 不支持某一步，交付有范围约束、可重复执行的迁移命令及前后检查；禁止用手工填充片段或答案替代真实导入。

### 验收

- 旧冻结校验和离线评测测试仍通过，新增内容没有污染旧语料。
- 三个推荐问题均可从新版资料得到支撑；至少三个不同引用点击后定位正确，行号、哈希和原文一致。
- 改写问题仍可检索；没有资料支持的问题不编造客户数量、个人经历或功能。
- 上游循环/工具框架与本仓库新增能力的归属表述准确；不将幂等提交描述成任意外部调用 exactly-once。
- 候选导入失败时旧演示仍能用；旧公开会话和引用保持可访问。
- 前端推荐文案、提示词文件、实际 Agent 提示词、新 KB 和公开配置能一一对应。

### 交付

版本化资料与 manifest、提示词、真实样例、问题核对表、索引验证记录、发布/回退清单。向 UX-03 交付三个推荐问题及两条可追问方向；向 UX-05 交付简短问答测量题组。

<a id="ux-03"></a>
## UX-03：首页与首次提问

**目标：** 首次访客一眼知道能做什么，一次点击能开始体验，失败与重连不会重复执行。

### 代码入口

- [EmptyAgentChatView](../../../ui/src/components/views/agentChatView/EmptyAgentChatView.tsx)、[config](../../../ui/src/config.ts)
- [AgentChatView](../../../ui/src/components/views/AgentChatView.tsx)、[AgentChatInput](../../../ui/src/components/views/agentChatView/AgentChatInput.tsx)
- [Sidebar](../../../ui/src/layout/Sidebar.tsx)、[PublicShowcaseView](../../../ui/src/components/views/PublicShowcaseView.tsx)
- [api](../../../ui/src/api/api.ts)、[http](../../../ui/src/api/http.ts)

### 实施步骤

1. 接收并核对现有未提交主题与 R2 发送修复，明确合并基线。沿用现有视觉组件和主题，主要调整信息层次、间距与交互。
2. 首页只保留一个明显主行动，三个推荐问题用简洁入口呈现。手机首屏能看到用途、主按钮和至少一个具体问题，弱化首次无历史时的空记录区域。
3. 推荐问题点击后直接进入个人会话并发送；按钮文案明确该行为。助手尚未准备好或连接失败时显示可理解的状态与重试入口。
4. 首页推荐、手动发送、示例页「亲自试一试」、相关追问统一经过发送协调器。可提取 hook，但须保留现有稳定幂等键、受理成功的统一收尾和终态重放处理。
5. 实现 [发送意图契约](contracts.md#submission)：快双击、POST 响应丢失、手动重试、SSE 重连及刷新恢复均不能启动第二个相同意图。不要为了自动提交而新增第二个 Effect。
6. 输入区区分可编辑草稿、可发送、可取消三个状态。等待时可以准备下一条草稿；同一会话仍只有一个活动运行。已知 runId 后的停止按钮调用真实取消接口，不能仅隐藏 loading。
7. 完成后提供最多两条相关追问，通过统一发送入口处理。答案查看、展开内容等阅读操作不发起模型请求。
8. 手机使用可用视口高度与安全区，测试软键盘打开/关闭。推荐卡片不应无故弹出键盘；手动输入的发送键、焦点与 Enter/Shift+Enter 行为保持明确。

### 视觉约束

- 默认界面优先展示问题与答案；Agent ID、模型目录、索引版本和长工具名进入详细信息层。
- 主要交互点击区域建议至少 44px；图标按钮有可读名称和键盘焦点。
- 首次进入和返回历史是两个真实状态，不把已有访客的历史隐藏掉。
- 在 320、390、768、1366px 宽度检查，没有横向溢出、遮挡或无法访问的主按钮。

### 验收

- 使用真实浏览器，从三个推荐入口各发起一次，均只产生一个发送意图与一个用户问题。
- 自动首条响应丢失 → 手动同键重试 → SSE 实际断开并重连，仍是一个运行；终态重放恢复输入。
- 刷新已受理的会话恢复运行/答案，不自动重发；未知提交状态的恢复保留同一键或先查询确认，不能换键自动发送。
- 停止操作可达且调用后端；请求取消不等于立即显示终态。
- 页面切换后旧请求或 SSE 不覆盖当前会话；游客与账号构建分别通过。
- 记录手机与桌面首屏、加载态、失败态和成功态截图。新样式不破坏既有资料预览及账号页面。

### 交付

前端提交、统一发送入口说明、关键恢复场景证据、截图。交接给 UX-04 时标出共享组件与 Props 的最终接口。

<a id="ux-04"></a>
## UX-04：回答、证据与执行过程的阅读体验

**目标：** 用户先读到答案，再按需要核对原文和执行过程；页面不会把技术记录堆成主体，也不会隐藏失败。

### 代码入口

- [AgentChatHistory](../../../ui/src/components/views/agentChatView/AgentChatHistory.tsx)
- [DocumentPreviewDrawer](../../../ui/src/components/modals/DocumentPreviewDrawer.tsx)、[预览样式](../../../ui/src/components/modals/DocumentPreviewDrawer.css)
- [execution API](../../../ui/src/api/execution.ts)、[运行类型](../../../ui/src/types/execution.ts)、[引用类型](../../../ui/src/types/index.ts)
- 与 UX-03 共用的 `AgentChatView`、`PublicShowcaseView`。按同一前端负责人顺序集成。

### 实施步骤

1. 将一轮执行整理为「问题、活动状态或最终答案、来源、可展开过程」的阅读单元。由 runId 与服务端关联信息归组，不按相邻消息位置猜测；旧记录保留可读的降级展示。
2. 合并同一运行的工具调用和结果到一处执行过程。默认显示状态和必要耗时；展开后可看真实步骤与工具详情。FAILED / CANCELLED / INTERRUPTED 的原因仍放在问题附近。
3. 将运行列表与详情读取实现为明确的 loading / ready / error 状态。请求失败时不清空后伪装成成功空列表；首次失败展示一次可重试提示，后台刷新失败保留上次成功数据并注明尚未更新。
4. 默认显示紧凑来源入口，可按文档分组并显示片段数；展开后保留每一处不同的证据与位置。文档同名不代表同一来源，分组使用 documentId 和版本/哈希。
5. 复用现有授权原文预览与定位逻辑。新版来源点击后直接显示目标段落和上下文；旧版来源缺位置、源内容已改变、加载失败三类情况分别说明。
6. 处理 [阅读位置契约](contracts.md#reading-position)。首次完整答案到达且用户正在跟随本轮时，定位到答案开头；用户在读旧消息时保持位置，并提供「查看新回答」。来源展开、详情加载、窗口缩放不强制滚动到底。
7. 对超长回答用可访问的「展开完整回答」阅读容器，完整内容仍保留，复制操作有明确范围。不能截断 Markdown 或只展示前几个字符来伪装成短答。
8. 处理请求并发：切换会话时取消旧读取或按会话版本丢弃响应；轮询只在可见页面有活动运行时进行，终态停止。详情失败可手动重试。
9. 只读示例复用相同阅读体验，但不能因共享组件而出现取消、续写或订阅其私有 SSE 的能力。保留明确的「亲自试一试」入口。

### 推荐展示

- 答案下：「来源：项目导览 · 工程案例」「共 3 处引用」，点击后展开具体来源。
- 执行过程：「已完成 · 执行 8.2 秒 · 查看过程」。数字来自服务端；缺失时不编造。
- 运行读取失败：「执行记录暂时无法加载，重试」。这不替代已经成功取得的答案。
- 历史确实没有记录：「这条历史消息没有执行记录」，每轮至多一次。

### 验收

- 本轮基线中的 1,655 字长回答和 6 条来源，在桌面/手机打开后能从答案开头阅读。
- 用户上滚、选中文字、打开来源、展开过程时，不被后续消息或加载结果拉走。
- Nginx HTML 404、JSON 404、500、断网均显示加载问题，不显示「未保存」；成功空列表才进入相应历史状态。
- 新版引用定位正确；旧引用可读；哈希不符时不宣称已精确命中当前原文。
- 无答案的失败/取消/中断在刷新后仍能定位到自己的问题。
- 320px、390px 和桌面均无横向溢出；键盘可打开/关闭来源与过程，关闭后焦点返回触发位置。

### 交付

阅读组件提交、前后截图、滚动与错误态浏览器证据、引用与运行分组说明。提供给 UX-05 可观测的首答节点与状态节点。

<a id="ux-05"></a>
## UX-05：首答体验、真实进度与性能

**目标：** 缩短用户等待有效内容的时间，并以真实测量区分模型、检索和界面的耗时。

### 代码入口

- [JChatMind](../../../jchatmind/src/main/java/com/kama/jchatmind/agent/JChatMind.java) 的 `requestModel`、`callModelWithRetry`、工具执行与答复持久化路径
- [RunExecutor](../../../jchatmind/src/main/java/com/kama/jchatmind/runs/RunExecutor.java)、[RunLease](../../../jchatmind/src/main/java/com/kama/jchatmind/runs/RunLease.java)
- [执行事件类型](../../../ui/src/types/execution.ts)、UX-03/04 的最终前端版本、UX-02 的实际提示词

### 第一批必须实施

1. 固定新版资料、测试问题、模型、配置与网络环境，建立优化前测量。旧版资料上的 19.752 秒仅为体验观察，不用来直接计算新版的提升比例。
2. 前端分别记录点击、受理、收到真实阶段事件、首次最终答案正文可见、答复完成的时间。后端使用已保存的排队、模型调用和检索事件归因。口径见 [性能测量](acceptance.md#performance)。
3. 依据事件显示实际进展：分析问题、检索资料、整理回答、正在取消或等待服务响应。只有获得实际证据后才能显示找到多少资料；已有枚举不能支持的具体状态保持概括，不用计时器编造阶段。
4. 等待超过 10 秒时，显示真实已等待时间及可用操作。SSE 断开时显示连接恢复状态，恢复后按运行终态收敛。
5. 用 UX-02 的短答策略减少无关段落；核对实际 Agent 提示词与文件一致。内容调整由资料负责人合并，防止线上和仓库出现两套提示词。
6. 测量是否存在同一轮不必要的重复检索、重复模型调用或重复前端刷新。优先修复有证据的重复工作。
7. 每次只比较一个有意义的策略变化，记录准确性、引用和 token 用量。低延迟不能以错误来源、遗漏关键结论或绕过任务守卫换取。

### 性能目标与决策

UI 反馈和状态真实性是硬性要求。`T_first_answer`（首次真实答案正文可见）的 p50 ≤ 10 秒、p95 ≤ 20 秒是本轮优化目标，同时报告 `T_final_answer`；达标与否按实测报告，这些数字不是当前能力声明。若外部服务限制仍使目标不可达，交付分段耗时、已做优化与下一步方案，不把未达标写成通过。

### 根据测量选择的后续工作

| 候选 | 进入条件 | 约束 |
| --- | --- | --- |
| 模型或生成参数调整 | 主要延迟确实来自模型且候选服务可用 | 同一题组复测事实、引用、失败率与成本，保留原配置可回退 |
| 检索或 Embedding 缓存 | 发现可重复的相同工作且能测得收益 | 键包含权限范围、KB/索引版本、模型和查询；不能跨身份泄露内容 |
| 真正的答案流式输出 | 短答优化后，长时间无正文仍是主要问题 | 先交付流式协议设计，再实现；遵守取消、租约和最终提交边界 |

当前模型调用为同步 `.call()`，最终回答可能在工具参数中。逐字播放已完成的答案不会缩短首答时间，也不能记为流式实现。流式方案见 [后续流式契约](contracts.md#streaming)。

### 验收与交付

- 真实问题的阶段展示可与实际事件对应；不能先显示「已检索」后才真正请求检索。
- 重复提交、取消、过期租约、被接管 worker 的行为不回退。
- 同口径测量报告包含样本量、逐次结果、分位数、失败/超时、答案质量与用量；未执行项明确标注。
- 输出优化后的版本、原始测量数据和一份性能结论。若实现流式，必须另附协议、断连与取消验证，不能只附演示 GIF。

<a id="ux-06"></a>
## UX-06：集成验收、发布与线上复核

**目标：** 验收同一份待发布代码与数据，确认实际游客路径达到要求，并能回退。

### 实施步骤

1. 接收 01–05 的提交和交付记录，解决共享文件冲突；保留现有主题和此前 R2 修复。形成明确的候选提交，不从混有其他工作内容的目录随手打包。
2. 在隔离环境运行 [验收矩阵](acceptance.md)，使用真实 Chromium、真实 PostgreSQL 和实际反向代理。HTTP 故障注入在隔离环境执行。
3. 完成前端 lint 与游客/账号双构建、适用的后端与冻结评测检查。通过后不要无变化地反复重跑整套测试；新修复只补相关检查，最后核对候选版本。
4. 准备一个发布清单，包含后端 JAR/镜像、两份前端构建、网关差异、公开资料哈希、索引版本、Agent 提示词/KB 范围及示例清单。每项标记是本次更新还是沿用已核对的版本。
5. 在发布前重新检查线上状态，避免覆盖期间其他 agent 的新发布。生成当前状态的可用备份与回退指令，而不是直接套用旧 R5 备份。
6. 按 [发布顺序](acceptance.md#release) 应用兼容的接口与网关、前端、演示资料配置。需要重启才生效的配置必须验证实际运行值。
7. 使用全新游客身份进行线上真实新提问、引用定位、详情读取、刷新恢复；另核对一个旧公开示例与一个账号入口。仅检查首页/健康接口不构成完成。
8. 记录线上提交、资源指纹、资料/索引版本和浏览器证据；正常回退优先切换代码、配置与资源指针，不清空数据库或删除历史。

### 代码与证据入口

- [Windows 检查脚本](../../../scripts/check.ps1)、[Linux 检查脚本](../../../scripts/check.sh)
- [RunResultGuardIntegrationTest](../../../jchatmind/src/test/java/com/kama/jchatmind/integration/RunResultGuardIntegrationTest.java)
- [RunQueueIntegrationTest](../../../jchatmind/src/test/java/com/kama/jchatmind/integration/RunQueueIntegrationTest.java)
- [RetrievalIndexIntegrationTest](../../../jchatmind/src/test/java/com/kama/jchatmind/integration/RetrievalIndexIntegrationTest.java)、[IngestionJobIntegrationTest](../../../jchatmind/src/test/java/com/kama/jchatmind/integration/IngestionJobIntegrationTest.java)
- 本机原始 R5 验收脚本位于 `E:/ali-ssh/acceptance/20261019-fixes-r5/`。需要复用时将通用逻辑整理成仓库内可配置脚本，不复制私密浏览器状态或本机配置。

### 最终交付

`deliveries/UX-06.md`、逐项验收矩阵、前后截图、性能与内容核对结果、发布清单、线上冒烟、回退说明。汇报「已提交」「已部署」「已验证」三个状态；任何一项仍不满足时，清楚列出剩余工作。
