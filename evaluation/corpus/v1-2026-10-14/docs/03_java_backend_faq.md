# Java 后端技术问答：ExoBrain 工程入门

《Java 后端技术问答》是根据公开官方文档和 ExoBrain 源码自写的入门资料，核对日期为 2026 年 10 月 14 日。本文介绍后端组件的职责，以及请求处理、工具调用、实时推送和数据保存怎样配合，帮助读者理解项目当前的工程实现。

## Java、JDK 与 Spring Boot 分别负责什么

《Java 后端技术问答》：Java 是本项目后端使用的编程语言。JDK 是开发工具包，提供编译和运行 Java 程序所需的工具；Java 源码通常编译为字节码，由 Java 虚拟机执行。Spring Boot 为后端应用组织配置和常用组件，便于运行 Web 服务。ExoBrain 当前部署使用 Java 21，部署说明要求 JDK 21，容器镜像也采用 Java 21 运行时。理解这些名称时，可以分别对应“写业务的语言”“开发工具”“应用框架”。

来源：[Dev.java 入门文档](https://dev.java/learn/getting-started/)、[Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。项目依据：部署说明与部署 Dockerfile。

## ExoBrain 后端的 Controller、Service 和 Mapper 怎样分工

《Java 后端技术问答》：Controller 接收并响应 Web 请求；Service 组织业务步骤；Mapper 通过数据库语句读取或保存数据。聊天请求与文档上传是不同的业务流程：公开聊天由专用入口检查访客权限和会话归属，保存用户消息后交给后台执行；管理聊天则在保存用户消息后发布事件，由异步监听器启动 Agent。文档上传入口负责接收资料，其业务服务协调文件保存、Markdown 解析、向量生成和片段入库，它不是聊天请求的入口。这些职责划分便于分别检查请求处理、检索或数据保存的问题，并不表示系统已经拆成多个独立微服务。

源码依据：公开聊天入口为 DemoController → DemoChatService → DemoExecutionService；管理聊天入口为 ChatMessageController → ChatMessageFacadeServiceImpl → ChatEventListener。文档上传入口为 DocumentController → DocumentFacadeServiceImpl，片段入库使用 ChunkBgeM3Mapper 与对应 Mapper XML。

## Spring 依赖注入是什么，KnowledgeTool 为什么可以调用检索服务

《Java 后端技术问答》：依赖注入表示对象需要的协作者由容器提供，而不是在每个业务方法里自行创建。例如 KnowledgeTools 通过构造器获得 RagService，再由该服务完成向量检索。这样工具可以专注于输入和输出，检索服务专注于检索逻辑，也便于测试时替换协作者。源码中的工具发现还利用 Spring 收集实现 Tool 接口的组件，再按固定工具和可选工具装配到 Agent。

源码依据：KnowledgeTools.java、ToolFacadeServiceImpl.java、JChatMindFactory.java。概念来源：[Spring 依赖注入说明](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)。

## ExoBrain SSE 实时状态和数据库会话历史有什么区别

《Java 后端技术问答》：SSE 是服务器向浏览器持续发送事件的一种方式。ExoBrain 当前推送的是处理状态和完整消息事件，并非模型逐 token 的流式输出。一次聊天先把用户消息保存到数据库，再异步启动 Agent；执行期间，助手消息和工具结果在各自产生时先保存，需要展示的已保存消息随后通过 SSE 推送。数据库保存贯穿执行流程，不是等 SSE 推送结束后才统一落库。处理状态事件则用于提示正在思考、执行或已经结束。

实时推送与历史保存承担不同任务：SSE 连接断开不会删除此前保存的消息，也不会仅因推送失败就中断 Agent 执行；页面可以重新读取数据库中的历史。只看到某个状态事件，不能据此认定回答已正确完成。

源码依据：DemoExecutionService.submit、ChatMessageFacadeServiceImpl.createChatMessage、ChatEventListener.handle；JChatMind 的 saveMessage、refreshPendingMessages、trySend 与 callModelWithRetry；SseServiceImpl。通用机制来源：[Spring MVC 异步请求说明](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)。

## 文档索引失败时为什么需要事务与文件清理

《Java 后端技术问答》：一次文档上传可能先创建记录，再保存文件、生成向量并写入片段。数据库事务用于把相关数据库操作作为一个整体处理，减少只完成一半的数据状态。ExoBrain 的上传服务使用事务，并在失败或回滚时安排清理已保存文件，因为普通文件不会自动随数据库事务回滚。源码包含这类失败处理，但没有给出生产环境故障恢复时间或成功率数据，不能据此作性能保证。

源码依据：DocumentFacadeServiceImpl.java。概念来源：[Spring 的事务注解说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)。
