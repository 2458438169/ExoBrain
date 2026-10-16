# 01 — Markdown 解析并发隔离

**优先级：最高。前置：无。消费者：03、06。**

## 可直接分发的提示词

> 请执行 ExoBrain 开发任务 01。先阅读 docs/development/README.md、docs/development/contracts.md 和 docs/development/tasks/01-parser-isolation.md。修复 Markdown 解析服务中请求正文存入单例字段的并发风险，补有意义的并发回归，保持现有上传和权限行为。只修改本任务负责的代码与测试；不重做 RAG、不部署、不改其他 Agent 的未提交文件。完成后提供实际提交、测试结果及 docs/development/deliveries/01.md，明确哪些风险已经复现、哪些仅由静态检查发现。

## 代码入口与问题

- [MarkdownParserServiceImpl](../../../jchatmind/src/main/java/com/kama/jchatmind/service/impl/MarkdownParserServiceImpl.java)：`@Service` 单例；`originalMarkdownContent` 每次解析被赋值，之后又被解析和表格提取读取。
- [MarkdownParserService](../../../jchatmind/src/main/java/com/kama/jchatmind/service/MarkdownParserService.java)：当前返回 `MarkdownSection(title, content)`。
- [DocumentFacadeServiceImpl](../../../jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java)：调用解析器的同步上传入口。
- [DocumentFacadeServiceImplTest](../../../jchatmind/src/test/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImplTest.java)：已有上传、事务失败补偿测试。

两个上传可在字段写入、读取间互相覆盖。风险不只在表格分支，`parser.parse(originalMarkdownContent)` 自身也再次读共享字段。**当前没有已确认的线上串文事件，不得在报告中虚构泄漏事实。**

## 实施范围

1. 把正文、AST 与提取状态限定在一次调用中。优先局部不可变变量、参数传递或 AST 自身的原文切片，不用静态字段、共享 ThreadLocal 或给整个解析服务加 synchronized 来掩盖共享状态。
2. 核查当前 flexmark 版本对 Parser 实例并发使用的约定；不能仅凭字段 final 推断线程安全。必要时使用每次调用创建的解析器/受控工厂，配置可复用但请求状态不复用。
3. 维持公开解析接口和现有章节提取语义。来源行号、正文切块由 03 后续扩展，01 不提前改 DTO 造成冲突。
4. 检查表格分支是否真的可达。基线构造器未显式配置 TablesExtension，测试含表格文本不等于执行了 TableBlock 分支；若启用扩展引起解析行为变化，应单独说明并测试。
5. 异常不得在日志中新增原始私有正文。保留 UTF-8 输入、表格/代码/中文内容处理和上传失败的已有补偿行为。

## 验证要求

- 同一服务实例并行解析至少 16 份互不相同的合成 Markdown，多轮执行；每份标题、正文、表格与代码中放入唯一标记。
- 每个结果只能包含自己的标记，不能只断言章节数。不同长度、重复标题、CRLF、中文和空输入都需要覆盖。
- 使用 barrier/latch 等控制并发起跑并限制等待时间，失败能打印合成样本编号。避免依靠 sleep 碰运气和没有上限的循环。
- 可提供包内解析器工厂/构造注入以控制调度，但不 mock 最终解析结果来“证明”无污染。不能稳定复现旧实现时，如实标注“风险由静态检查确认，并发回归验证修复后的隔离”，不声称完成确定性复现。
- 加一条真实解析器接入上传路径的双文档集成检查，分别核对 KB/doc 与 chunk 内容；数据使用临时目录及隔离数据库。不要用线上私有资料。
- 运行已有上传失败补偿与文档存储测试。此任务不应改变数据库结构和接口返回。

建议新增测试名 `MarkdownParserServiceImplTest` / `MarkdownParserConcurrencyTest`。在 `jchatmind/` 中用 Java 21 的 Maven Wrapper 执行真实存在的测试名，记录完整命令；不要把建议名称当成已有测试。

## 完成标准与交接

- 无单例请求正文/解析状态；并发结果逐份对应正确。
- 当前单文档解析行为与上传事务回归通过，旧测试没有为了通过而删掉断言。
- 交付说明共享 Parser 的线程安全依据或替代设计，给出并发样本数、轮数、耗时、失败/跳过情况。
- 告知 03 哪些私有方法被调整、哪些表格语义保持或变更；不先实现正文索引、检索阈值、引用或后台队列。
