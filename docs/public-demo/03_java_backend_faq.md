# Java 后端技术问答：面向 HR 的 ExoBrain 说明

《Java 后端技术问答》是根据公开官方文档和 ExoBrain 源码自写的入门资料，核对日期为 2026 年 10 月 14 日。问题用于理解项目工程职责，不是候选人的评分规则，也不包含真实简历、联系方式或面试记录。

## Java、JDK 与 Spring Boot 分别负责什么

《Java 后端技术问答》：Java 是本项目后端使用的编程语言。JDK 是开发工具包，提供编译和运行 Java 程序所需的工具；Java 源码通常编译为字节码，由 Java 虚拟机执行。Spring Boot 为后端应用组织配置和常用组件，便于运行 Web 服务。ExoBrain 当前部署使用 Java 21，部署说明要求 JDK 21，容器镜像也采用 Java 21 运行时。理解这些名称时，可以分别对应“写业务的语言”“开发工具”“应用框架”。

来源：[Dev.java 入门文档](https://dev.java/learn/getting-started/)、[Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。项目依据：部署说明与部署 Dockerfile。

## ExoBrain 后端的 Controller、Service 和 Mapper 怎样分工

《Java 后端技术问答》：Controller 接收并响应 Web 请求；Service 组织业务步骤；Mapper 通过数据库语句读取或保存数据。以文档索引为例，业务服务协调文件保存、Markdown 解析、向量生成和片段入库，数据库访问由 Mapper 承担。拆开这些职责后，可以单独检查解析、检索或数据保存的问题。它们都属于同一条业务流程中的分工，并不意味着系统已经拆成多个独立微服务。

源码依据：DocumentController.java、DocumentFacadeServiceImpl.java、ChunkBgeM3Mapper.java 与对应 Mapper XML。

## Spring 依赖注入是什么，KnowledgeTool 为什么可以调用检索服务

《Java 后端技术问答》：依赖注入表示对象需要的协作者由容器提供，而不是在每个业务方法里自行创建。例如 KnowledgeTools 通过构造器获得 RagService，再由该服务完成向量检索。这样工具可以专注于输入和输出，检索服务专注于检索逻辑，也便于测试时替换协作者。源码中的工具发现还利用 Spring 收集实现 Tool 接口的组件，再按固定工具和可选工具装配到 Agent。

源码依据：KnowledgeTools.java、ToolFacadeServiceImpl.java、JChatMindFactory.java。概念来源：[Spring 依赖注入说明](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)。

## ExoBrain SSE 实时状态和数据库会话历史有什么区别

《Java 后端技术问答》：SSE 是服务器向浏览器持续发送事件的一种方式。ExoBrain 使用它推送处理状态和生成内容，让页面知道当前正在处理还是已经结束。数据库中的会话消息则用于保存和重新读取历史。实时推送与历史保存承担不同任务：浏览器连接关闭，不代表此前保存的消息已被删除；只看到某个状态事件，也不能据此认定回答已正确完成。源码包含异步执行流程，方便请求受理后继续在后台处理。

源码依据：SseServiceImpl.java、JChatMind.java、ChatMessageFacadeServiceImpl.java、ChatEventListener.java。通用机制来源：[Spring MVC 异步请求说明](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)。

## 文档索引失败时为什么需要事务与文件清理

《Java 后端技术问答》：一次文档上传可能先创建记录，再保存文件、生成向量并写入片段。数据库事务用于把相关数据库操作作为一个整体处理，减少只完成一半的数据状态。ExoBrain 的上传服务使用事务，并在失败或回滚时安排清理已保存文件，因为普通文件不会自动随数据库事务回滚。源码包含这类失败处理，但没有给出生产环境故障恢复时间或成功率数据，不能据此作性能保证。

源码依据：DocumentFacadeServiceImpl.java。概念来源：[Spring 的事务注解说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)。
