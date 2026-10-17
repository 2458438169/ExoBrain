# 来源与能力归属

本文件说明 ExoBrain 与上游项目的来源关系、保留的许可，以及本仓库实际新增的能力范围。所有判断以仓库中可核对的代码、脚本与提交记录为依据，不使用无法核对的表述。

## 1. 上游来源与许可

- 本项目基于上游 **JChatMind**（<https://github.com/youngyangyang04/JChatMind>）持续开发。上游作者的版权声明与 [MIT License](../LICENSE) 完整保留：`LICENSE` 为 MIT 文本，版权行为 `Copyright (c) 2025 程序员Carl`。
- Java 包名沿用 `com.kama.jchatmind`，上游模块结构（`jchatmind/`、`ui/`、`db/`）与类名保持一致，便于对照上游问题与解答。
- 使用、修改和再分发时必须保留原许可与版权声明。本仓库不对上游历史做改写或抹除来源的说明。
- **提交历史说明**：本仓库的 Git 历史由本项目账号记录，不包含上游仓库的原始提交 SHA。因此本文用“代码能力归属”而不是上游提交号来描述继承关系；需要逐行对照时应以具体文件与上游仓库当前内容比对。
- 未添加任何未被要求的协作者或贡献者署名；提交作者以仓库历史实际记录为准。

## 2. 从上游继承的能力

以下能力来自上游 JChatMind，本项目在其基础上维护与修正：

| 能力 | 主要位置 |
| --- | --- |
| Think-Execute 循环 + 状态机、步骤上限与终止路径 | `agent/JChatMind.java`、`agent/AgentState.java` |
| 关闭 Spring AI 内部工具执行（`internalToolExecutionEnabled(false)`）、自行执行并记录工具调用与回填 | `agent/JChatMind.java`、`agent/JChatMindFactory.java` |
| 工具框架：`Tool` / `ToolType` / `@Tool` 声明、集合注入发现、`MethodToolCallbackProvider` 转换、固定与可选工具 | `agent/tools/**`、`service/ToolFacadeService*` |
| Agent 作为数据：`agent` 表保存 `system_prompt`、`model`、`allowed_tools`、`allowed_kbs`、`chat_options`；按配置装配运行时 | `agent/JChatMindFactory.java`、`db/jchatmind.sql` |
| 会话与消息持久化、历史查询、工具调用与结果保存到消息 `metadata` | `service/impl/ChatMessageFacadeServiceImpl.java`、`ChatSessionFacadeServiceImpl.java` |
| Markdown 解析为章节、以标题生成向量、正文与向量写入 `chunk_bge_m3`；`ivfflat (vector_l2_ops)`、按知识库过滤并按 L2 距离取 3 条 | `service/impl/MarkdownParserServiceImpl.java`、`service/impl/DocumentFacadeServiceImpl.java`、`service/impl/RagServiceImpl.java`、`resources/mapper/ChunkBgeM3Mapper.xml` |
| SSE 推送状态与生成内容、历史拉取恢复界面 | `controller/SseController.java`、`service/impl/SseServiceImpl.java` |
| 前端脚手架与交互：React 19 + TypeScript + Ant Design、会话与知识库管理界面、工具调用展开 | `ui/src/**` |
| 部署承载方式：Dockerfile、Compose、Nginx 反向代理模板 | `deploy/**` |

示例工具 `CityTool`（固定城市）、`WeatherTool`（模拟天气）同样继承自上游，属演示用途，`prod` profile 下不注册，也不能用于实时定位或天气数据。

## 3. 本仓库新增的能力

以下能力是在本仓库中新增或重做的（上游项目未提供对应实现），共同点是都要求服务端核验身份与资源归属：

| 能力 | 说明 | 主要位置 |
| --- | --- | --- |
| 账号、角色与会话 | 应用内登录页、服务端会话（库中存 token 哈希）、`ADMIN` / `USER` 角色、PBKDF2 口令、CSRF、同源校验、登录限速、账号启停与改密后撤销会话 | `auth/**`、`deploy/migrations/20261014-accounts.sql` |
| 私有资源隔离 | 知识库、文档、助手、会话按账号归属（`exobrain_resource_acl`）；管理员也不能访问其他账号的私有资源；公开资源对普通用户只读 | `auth/ResourceAccess.java`、`auth/ResourceAclRepository.java` |
| 后台执行的权限传播 | 执行线程不依赖 `ThreadLocal` 身份，使用提交时快照并在每次工具调用前复核；知识库工具参数按助手获准范围校验 | `event/listener/ChatEventListener.java`、`auth/ScopedToolCallback.java`、`docs/adr/0001-background-authorization.md` |
| 系统模型目录与密钥管理 | `exobrain_model` / `exobrain_model_state` / `exobrain_kb_embedding`；LLM 与 Embedding 目录、启停、测试连接、API Key 使用 AES-GCM 加密；知识库固定绑定 Embedding 模型与 1024 维 | `models/**`、`config/ChatClientRegistry.java`、`deploy/migrations/20261014-models.sql` |
| 游客免登录入口（独立执行器） | 访客 Cookie 身份、发布白名单（`public-kb-ids` / `showcase-session-ids`）、固定四工具白名单、只读精选示例 | `demo/**` |
| Markdown 在线预览 | 游客与管理端两个原文接口、2 MiB 上限、UTF-8/BOM 处理、不返回存储路径 | `controller/DocumentController.java`、`service/impl/DocumentStorageServiceImpl.java` |
| 删除与持久文件清理 | 删除不留下可检索索引；文件清理写入 `exobrain_storage_cleanup` 并在提交后执行，失败持久重试 | `service/StorageCleanupService.java`、`service/impl/KnowledgeBaseFacadeServiceImpl.java`、`deploy/migrations/20261016-storage-cleanup.sql` |
| 解析器并发隔离 | 移除单例中的请求正文状态，每次解析独立（任务 01） | `service/impl/MarkdownParserServiceImpl.java`、`docs/development/deliveries/01.md` |
| 可重复检查与 CI | 统一检查脚本（`scripts/check.sh` / `scripts/check.ps1`）、测试分类 profile、GitHub Actions 无凭据工作流 | `scripts/**`、`.github/workflows/ci.yml`、`jchatmind/pom.xml` |

### 计划中但**尚未实现**的能力

`docs/development/tasks/` 的 03–07 目前是待实施方案，不属于现有能力：正文切块与索引版本化（03）、可核验引用与点击定位（04）、执行追踪与真实耗时用量（05）、持久化文档导入（06）、持久化对话任务与恢复（07）。它们落地前，公开说明中不得出现“引用已核验”“任务可恢复”“自动模型降级”等描述；[架构文档](architecture.md) 第 5–8 节列出了当前边界。

## 4. 资料、语料与示例

- 公开示例资料 `docs/public-demo/01_exobrain_overview.md`、`02_rag_principles.md`、`03_java_backend_faq.md` 为本项目自写内容，基于本仓库源码与官方技术资料整理，用于公开知识库与体验问答；`99_test_questions.md` 与 `agent-system-prompt.md` 仅供维护复核，不入库。
- `evaluation/` 下的冻结语料、题集与运行脚本、评测报告由任务 02 产出，属本项目自建评测资产，不来自上游。
- 开发过程文档（`docs/development/**`）是本项目的任务书、契约与交付记录，属于工程过程资料，不是产品能力声明；其中的“待实现”条目未上线。
- 仓库不包含生产凭据、私有语料或第三方受版权保护的资料；模型 API Key、数据库口令与密钥文件均位于仓库之外。
