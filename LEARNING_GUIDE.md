# DataPilot 学习导读（Supervisor 多智能体版）

这份文档是给你看源码用的学习文件。它会重点解释：
- 目录是做什么的
- 核心类的职责是什么
- 每个核心方法是干什么的
- 你现在最容易陌生的语法糖和新概念

这次大重构之后，项目和之前版本有本质区别，最重要的变化有 5 个：

1. **从假 Agent 到真 Agent**：以前 Tool 调用写死在 Java 代码里，现在 100% 由大模型 Function Calling 自主调度
2. **从无记忆到多轮对话**：基于 conversationId + MessageWindowChatMemory，支持追问和上下文关联
3. **从静态路由到 Supervisor 模式**：删除了 RouterAgent / RagAgent / DbaAgent，只留一个 SupervisorAgent，由大模型自己判断该调谁
4. **从单数据源到动态数据源**：目标数据库通过 REST API 动态增删，控制面与数据面物理隔离
5. **Spring AI 0.8.1 -> 1.0.0 GA**：升级到正式发布版本，API 更简洁、功能更完整

所以你现在再看这个项目时，要把它当成一个：

> 传统 Spring Boot 项目 + AI 多智能体调度 + 对象存储 + 异步文档处理 + 动态数据源管理

而不是只把它当成一个单纯的三层 CRUD 项目。

---

## 1. 现在这个项目到底在做什么？

一句话概括：

> 用户上传文档后，系统把文件上传到 OSS，再异步解析并写入 Milvus；用户聊天时，SupervisorAgent 自主判断调用知识库 Agent 还是 DBA Agent，Tool 调用全部由大模型决策。

当前有四条核心业务链路：

### 1.1 文档上传链路
```text
用户上传文件
-> DocumentController
-> DocumentUploadService
-> 临时文件
-> ObjectStorageService 上传到 OSS
-> knowledge_document 落库
-> RabbitMQ 发消息
```

### 1.2 文档解析入库链路
```text
RabbitMQ
-> DocumentMqListener
-> DocumentIngestService
-> ObjectStorageService 下载文件流
-> TikaDocumentParsingService 解析文本
-> DefaultDocumentSplittingService 切分文本
-> VectorStore 写入 Milvus
-> KnowledgeSegment 写入 MySQL
```

### 1.3 聊天问答链路（本次重构的核心）
```text
用户问题 (含可选 conversationId)
-> CopilotController
-> CopilotService (自动生成或传递 conversationId)
-> SupervisorAgent
   │  ChatClient 挂载了 chatMemory + askRagAgent + askDbaAgent
   │  大模型自己决定调谁、调几次
   │
   ├── 如果是知识类问题 → askRagAgent(question)
   │     内部又有一个 ChatClient，挂载了 milvusSearchTool
   │     大模型自己决定要不要检索向量库
   │     └── milvusSearchTool(keyword, topK)
   │
   └── 如果是数据库类问题 → askDbaAgent(question, instanceId)
         内部又有一个 ChatClient，挂载了 schemaReaderTool + sqlExecutorTool
         大模型自己决定：先读表结构 → 生成 SQL → 执行 → 发现错误 → 自动重试
         ├── schemaReaderTool(instanceId, tableName)
         └── sqlExecutorTool(instanceId, sql)

-> 返回 {conversationId, answer}
```

### 1.4 数据源管理链路（新增）
```text
管理员
-> DataSourceController
   POST   /api/datasources          → 新增目标数据源（写库 + 内存热加载）
   DELETE /api/datasources/{id}     → 移除并关闭连接池
   GET    /api/datasources          → 列出所有数据源（密码脱敏）
   POST   /api/datasources/{id}/test → 测试连接是否可用
-> TargetDatasourceMapper (数据库 CRUD)
-> TargetDataSourceRegistry (内存 Map 热加载)
```

---

## 2. 目录说明

```text
src/main/java/com/bloodstar/fluxragcompute
├── agent
├── common
├── config
├── controller
├── dto
├── entity
├── exception
├── mapper
├── mq
├── service
├── tools
└── utils
```

### `agent`
放 AI 角色类。

重构后只有一个类了：
- `SupervisorAgent`：多智能体主调度器

以前的 `RouterAgent`、`RagAgent`、`DbaAgent` 全部删除了。以前它们的逻辑去哪了？
- RouterAgent 的"路由判断"：现在由 SupervisorAgent 里的大模型自己判断
- RagAgent 的"查向量库"：迁入了 `SubAgentFunctionConfig.askRagAgent()`
- DbaAgent 的"读表结构 + 执行 SQL"：迁入了 `SubAgentFunctionConfig.askDbaAgent()`

### `common`
放统一响应和错误码（没变）：
- `ErrorCode`
- `BaseResponse`
- `ResultUtils`

### `config`
放配置类。这次重构新增了三个重要配置：
- `ChatMemoryConfig`：配置对话记忆
- `SubAgentFunctionConfig`：把 RAG 和 DBA 的逻辑包装成 Function Bean
- `TargetDataSourceRegistry`：动态数据源注册中心

原来就有的：
- `RabbitMqConfig`
- `MybatisPlusConfig`
- `OssProperties`
- `DocumentSplitProperties`

### `controller`
对外 HTTP 入口：
- `CopilotController`：聊天接口（重构了，传递 conversationId）
- `DocumentController`：文档上传接口（没变）
- `DataSourceController`：**新增**，管理目标数据源的 CRUD + 测试连接

### `dto`
放请求响应对象和中间数据对象：
- `ChatRequest`：聊天请求（新增了 `conversationId` 字段）
- `ChatResponse`：聊天响应（删除了 `target`/`reason`，新增 `conversationId`）
- `DataSourceRequest`：**新增**，新增数据源的请求体
- `ToolPayloads`：所有 Tool 相关的 Record（新增了 `RagAgentRequest`、`DbaAgentRequest`、`AgentResponse`，`SchemaReadRequest` 和 `SqlExecutionRequest` 加了 `instanceId`）
- `DocumentIngestMessage`（没变）
- `DocumentUploadResponse`（没变）
- `ParsedDocument`（没变）
- `StorageObjectInfo`（没变）

删除了：
- `RouteDecision`：以前是 RouterAgent 的路由结果，现在不需要了

### `entity`
数据库实体：
- `KnowledgeDocument`（没变）
- `KnowledgeSegment`（没变）
- `SlowQueryLog`（没变）
- `TargetDatasource`：**新增**，对应 target_datasource 表

### `exception`
异常体系（没变）：
- `BusinessException`
- `GlobalExceptionHandler`

### `mapper`
MyBatis-Plus Mapper 接口：
- `KnowledgeDocumentMapper`（没变）
- `KnowledgeSegmentMapper`（没变）
- `SlowQueryLogMapper`（没变）
- `TargetDatasourceMapper`：**新增**

### `mq`
RabbitMQ 消费者（没变）：
- `DocumentMqListener`

### `service`
业务服务层：
- `CopilotService`：**重构了**，简化成只转调 SupervisorAgent
- `SecuritySandboxService`（没变）
- `DocumentUploadService`（没变）
- `DocumentIngestService`（没变）
- `ObjectStorageService`（没变）
- `DocumentParsingService`（没变）
- `DocumentSplittingService`（没变）
- `service.impl` 下放具体实现（没变）

### `tools`
给 Agent 用的工具，现在全部是 Function Calling Bean：
- `MilvusSearchTool`：重构了，增加了 try-catch，SearchRequest API 升级
- `SchemaReaderTool`：重构了，注入 TargetDataSourceRegistry 替代 JdbcTemplate
- `SqlExecutorTool`：重构了，同上

### `utils`
小工具类（没变）：
- `ThrowUtils`

---

## 3. 你最应该先读什么？

这次重构的核心价值在聊天链路，所以建议先读这条线。

### 第一轮：聊天链路（重构核心）

1. `CopilotController`
2. `CopilotService`
3. `SupervisorAgent`
4. `ChatMemoryConfig`
5. `SubAgentFunctionConfig`（重点中的重点）
6. `SchemaReaderTool`
7. `SqlExecutorTool`
8. `MilvusSearchTool`

### 第二轮：动态数据源

9. `TargetDatasource`（实体）
10. `TargetDatasourceMapper`
11. `TargetDataSourceRegistry`（重点）
12. `DataSourceController`
13. `DataSourceRequest`

### 第三轮：DTO 变化

14. `ChatRequest`
15. `ChatResponse`
16. `ToolPayloads`

### 第四轮：文档上传链路（和上个版本变化不大，以前看过可以跳过）

17. `DocumentController`
18. `DocumentUploadService`
19. `AliyunOssStorageService`
20. `DocumentMqListener`
21. `DocumentIngestService`
22. `TikaDocumentParsingService`
23. `DefaultDocumentSplittingService`

### 第五轮：异常体系（和上个版本一样，以前看过可以跳过）

24. `ErrorCode`
25. `BusinessException`
26. `ThrowUtils`
27. `GlobalExceptionHandler`

---

# 4. 核心类详解

---

## 4.1 `CopilotController`

职责：
- 暴露聊天接口 `/api/chat`
- 把请求交给 `CopilotService`，自己什么逻辑都不做

完整代码：
```java
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CopilotController {

    private final CopilotService copilotService;

    @PostMapping("/chat")
    public BaseResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResultUtils.success(
                copilotService.chat(request.getConversationId(), request.getMessage()));
    }
}
```

### 和旧版的区别

旧版只传了 `request.getMessage()`：
```java
// 旧版
copilotService.chat(request.getMessage())
```

新版多传了一个 `conversationId`：
```java
// 新版
copilotService.chat(request.getConversationId(), request.getMessage())
```

为什么？因为现在支持多轮对话了。用户第一次不传 conversationId，系统会自动生成一个返回给用户；用户后续追问时带上这个 conversationId，系统就知道这是同一轮对话。

### 你应该怎么理解？
- `request.getConversationId()`：可能是 null（第一次聊天），可能有值（追问）
- `request.getMessage()`：用户说的话
- Controller 仍然很薄，符合"Controller 只做入口分发"的设计

---

## 4.2 `CopilotService`

职责：
- 处理 conversationId 的自动生成
- 转调 SupervisorAgent

完整代码：
```java
@Service
@RequiredArgsConstructor
public class CopilotService {

    private final SupervisorAgent supervisorAgent;

    public ChatResponse chat(String conversationId, String message) {
        if (!StringUtils.hasText(conversationId)) {
            conversationId = UUID.randomUUID().toString();
        }
        String answer = supervisorAgent.chat(conversationId, message);
        return ChatResponse.builder()
                .conversationId(conversationId)
                .answer(answer)
                .build();
    }
}
```

### 逐行解释

```java
if (!StringUtils.hasText(conversationId)) {
    conversationId = UUID.randomUUID().toString();
}
```
如果用户没传 conversationId（null 或空字符串），就自动生成一个 UUID。`StringUtils.hasText()` 是 Spring 的工具方法，null、空串、纯空格都返回 false。

```java
String answer = supervisorAgent.chat(conversationId, message);
```
把 conversationId 和用户消息交给 SupervisorAgent。SupervisorAgent 内部会：
1. 根据 conversationId 取出历史对话记录
2. 把历史记录 + 新消息一起发给大模型
3. 大模型自主决定调用哪个工具
4. 返回最终回答

```java
return ChatResponse.builder()
        .conversationId(conversationId)
        .answer(answer)
        .build();
```
构建响应，把 conversationId 返回给用户。用户下次追问时带上它就行。

### 和旧版的对比

旧版有三个依赖：
```java
// 旧版
private final RouterAgent routerAgent;
private final RagAgent ragAgent;
private final DbaAgent dbaAgent;
```

旧版的 chat 方法：
```java
// 旧版：手动路由 + 手动选择 Agent
RouteDecision decision = routerAgent.route(message);
String target = decision.normalizedTarget();
String answer = "DBA".equals(target) ? dbaAgent.answer(message) : ragAgent.answer(message);
```

新版只有一个依赖：
```java
// 新版
private final SupervisorAgent supervisorAgent;
```

新版的 chat 方法：
```java
// 新版：SupervisorAgent 内部全部搞定
String answer = supervisorAgent.chat(conversationId, message);
```

为什么能这么简？因为路由判断、Agent 选择、工具调用，全部交给大模型了。CopilotService 不需要知道有几个 Agent、该调谁。

---

## 4.3 `SupervisorAgent`（本次重构最核心的类）

职责：
- 项目的"大脑"
- 构建一个挂载了对话记忆 + 子 Agent 函数的 ChatClient
- 用户发来消息时，大模型自主决定调用哪个子 Agent

完整代码：
```java
@Component
public class SupervisorAgent {

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是 DataPilot 数据库智能运维主管。你负责理解用户意图，自主规划步骤，并调用下属专家工具收集信息后综合回答。

            ## 可用工具
            - askRagAgent(question)：知识库专家，适用于架构设计、模块职责、规范说明、系统原理等知识性问题
            - askDbaAgent(question, instanceId)：DBA 数据库专家，适用于 SQL 查询、慢查询分析、表结构查看、数据统计等

            ## 可用的目标数据库实例 ID
            %s

            ## 工作规则
            1. 根据用户问题判断应该调用哪个专家，可以多次调用不同专家收集信息
            2. 调用 askDbaAgent 时必须指定 instanceId，如果用户未指定，请询问用户选择
            3. 综合所有收集到的信息，给出完整、专业的回答
            4. 如果问题不明确，主动向用户澄清
            """;

    private final ChatClient chatClient;

    public SupervisorAgent(ChatModel chatModel,
                           ChatMemory chatMemory,
                           TargetDataSourceRegistry dsRegistry) {
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(dsRegistry.getAvailableInstanceIds());
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultToolNames("askRagAgent", "askDbaAgent")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String chat(String conversationId, String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
```

### 构造方法逐行解释

```java
public SupervisorAgent(ChatModel chatModel,
                       ChatMemory chatMemory,
                       TargetDataSourceRegistry dsRegistry) {
```
构造方法注入三个依赖：
- `ChatModel`：Spring AI 提供的大模型接口，背后连的是 ModelScope/OpenAI 兼容 API
- `ChatMemory`：对话记忆，由 ChatMemoryConfig 提供
- `TargetDataSourceRegistry`：动态数据源注册中心，这里只是用来获取可用实例 ID 列表

```java
String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(dsRegistry.getAvailableInstanceIds());
```
把可用的数据库实例 ID 列表动态注入到 System Prompt 中。比如如果当前有 `[db-test-01, db-prod-01]` 两个实例，那 System Prompt 里就会告诉大模型："可用的目标数据库实例 ID: [db-test-01, db-prod-01]"。

这样大模型在用户说"帮我查一下 db-test-01 的慢查询"时，就知道 db-test-01 是合法的实例 ID。

```java
this.chatClient = ChatClient.builder(chatModel)
```
用 ChatModel 创建一个 ChatClient 构建器。这是 Spring AI 1.0.0 的新 API，0.8.1 没有 builder 模式。

```java
        .defaultSystem(systemPrompt)
```
设置 System Prompt。每次调用 chatClient 时，这段系统提示词都会自动加在最前面，告诉大模型"你是谁、你能做什么"。

```java
        .defaultToolNames("askRagAgent", "askDbaAgent")
```
**这是 Function Calling 的关键一行。**

它告诉 Spring AI：这个 ChatClient 可以调用名为 `"askRagAgent"` 和 `"askDbaAgent"` 的两个函数。这两个名字对应的是 Spring 容器中两个 Bean 的名字（在 SubAgentFunctionConfig 中定义的）。

大模型在对话时会收到这两个函数的描述信息。如果大模型觉得需要调用，它会返回一个特殊的 JSON 格式的 function_call，Spring AI 框架自动帮你调用对应的 Bean，把结果返回给大模型，大模型再继续生成回答。

**你的 Java 代码里完全不需要写 if-else 判断该调谁。大模型自己决定。**

```java
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
```
挂载对话记忆 Advisor。Advisor 你可以理解成一个"拦截器"，它会在每次请求前：
1. 根据 conversationId 从 chatMemory 中取出历史消息
2. 把历史消息插入到这次请求的 prompt 里
3. 请求完成后，把新消息保存回 chatMemory

这样大模型就能"记住"之前聊过什么。

```java
        .build();
```
构建完成。

### chat 方法逐行解释

```java
public String chat(String conversationId, String userMessage) {
    return chatClient.prompt()
            .user(userMessage)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
}
```

- `.prompt()`：开始构建一次对话请求
- `.user(userMessage)`：设置用户说的话
- `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))`：把 conversationId 传给 Memory Advisor，让它知道要用哪个会话的历史记录。这是一个 lambda 表达式，`a` 是 advisor 的参数配置器。
- `.call()`：发送请求。这一步内部发生的事情非常多：
  1. Memory Advisor 取出历史消息
  2. System Prompt + 历史消息 + 用户消息 拼成完整的 prompt
  3. 发送给大模型
  4. 大模型可能返回 function_call（比如"我要调 askDbaAgent"）
  5. Spring AI 自动调用对应的 Function Bean
  6. 把 Function 的返回值再发给大模型
  7. 大模型可能再调另一个 function，也可能直接回答
  8. 循环直到大模型给出最终回答
  9. Memory Advisor 保存这轮对话
- `.content()`：取出最终的文本回答

### 你应该怎么理解？

这个类只有 50 行代码，但它做了以前 RouterAgent + RagAgent + DbaAgent + CopilotService 编排逻辑加在一起才能做的事情。

核心原因：它把"该调谁、调几次"的决策权完全交给了大模型。你的代码只负责：
1. 告诉大模型有哪些工具可用（`.defaultToolNames()`）
2. 告诉大模型它是谁（`.defaultSystem()`）
3. 帮它记住历史对话（`.defaultAdvisors()`）
4. 每次请求时把用户消息传过去（`.user()`）

剩下的全由大模型和 Spring AI 框架搞定。

---

## 4.4 `ChatMemoryConfig`

职责：
- 创建一个 ChatMemory Bean，供 SupervisorAgent 使用

完整代码：
```java
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }
}
```

### 逐行解释

```java
return MessageWindowChatMemory.builder()
        .maxMessages(20)
        .build();
```

`MessageWindowChatMemory` 是 Spring AI 1.0.0 内置的对话记忆实现。它的工作方式：
- 为每个 conversationId 维护一个消息列表
- 当消息数超过 maxMessages（这里设的 20），自动丢弃最早的消息
- 默认存在内存里（`InMemoryChatMemoryRepository`）

为什么叫"滑动窗口"？因为它永远只保留最近的 N 条，像一个固定大小的窗口在消息流上滑动。

如果 0.8.1 想实现这个功能，你得自己写一个 ConcurrentHashMap 来存历史消息，自己控制大小，自己拼到 prompt 里。1.0.0 一行代码就搞定了。

---

## 4.5 `SubAgentFunctionConfig`（重点中的重点）

职责：
- 把以前 RagAgent 和 DbaAgent 的核心逻辑，包装成 `Function<Request, Response>` Bean
- 这些 Bean 注册到 Spring 容器后，通过名字被 SupervisorAgent 的 ChatClient 挂载

这个类你需要仔细读，因为它是"以前三个 Agent 的逻辑去哪了"这个问题的答案。

### askRagAgent 方法

```java
@Bean("askRagAgent")
@Description("向知识库专家提问。适用于架构设计、模块职责、规范说明、系统原理等知识性问题。参数：question - 用户问题")
public Function<RagAgentRequest, AgentResponse> askRagAgent(ChatModel chatModel) {
    ChatClient ragClient = ChatClient.builder(chatModel)
            .defaultSystem(RAG_SYSTEM_PROMPT)
            .defaultToolNames("milvusSearchTool")
            .build();
    return request -> {
        String answer = ragClient.prompt()
                .user(request.question())
                .call()
                .content();
        return new AgentResponse(answer);
    };
}
```

逐行解释：

```java
@Bean("askRagAgent")
```
注册一个名为 `"askRagAgent"` 的 Spring Bean。SupervisorAgent 里的 `.defaultFunctions("askRagAgent")` 就是引用这个名字。

```java
@Description("向知识库专家提问。适用于架构设计、模块职责、规范说明、系统原理等知识性问题。参数：question - 用户问题")
```
这段描述会被 Spring AI 自动发送给大模型。大模型看到这段描述后，就知道什么时候该调用这个函数。

比如用户问"系统的文档入库链路是怎么设计的"，大模型会想：这是架构设计的知识性问题，应该调 askRagAgent。

```java
public Function<RagAgentRequest, AgentResponse> askRagAgent(ChatModel chatModel) {
```
返回类型是 `Function<RagAgentRequest, AgentResponse>`。Spring AI 会自动把 `RagAgentRequest` 的字段信息（`question`）告诉大模型，大模型调用时传入 JSON 参数，Spring AI 自动反序列化成 `RagAgentRequest` 对象。

```java
ChatClient ragClient = ChatClient.builder(chatModel)
        .defaultSystem(RAG_SYSTEM_PROMPT)
        .defaultToolNames("milvusSearchTool")
        .build();
```
给知识库 Agent 构建了它自己的 ChatClient。注意：
- 它有自己的 System Prompt（告诉它"你是知识库检索专家"）
- 它挂载了 `milvusSearchTool`（向量检索工具）
- 它没有挂载 Memory（子 Agent 不需要记忆，记忆由 Supervisor 管理）

这意味着知识库 Agent 在处理问题时，大模型会自己决定要不要调 milvusSearchTool 去向量库里搜索。

```java
return request -> {
    String answer = ragClient.prompt()
            .user(request.question())
            .call()
            .content();
    return new AgentResponse(answer);
};
```
返回一个 Lambda 函数。当 SupervisorAgent 的大模型决定调用 askRagAgent 时：
1. Spring AI 把大模型传的参数反序列化成 `RagAgentRequest`
2. 执行这个 Lambda
3. Lambda 内部用 ragClient 再跟大模型对话一轮（这轮对话可能会调用 milvusSearchTool）
4. 把最终回答包装成 `AgentResponse` 返回
5. Spring AI 把 `AgentResponse` 的内容发回给 SupervisorAgent 的大模型

### askDbaAgent 方法

```java
@Bean("askDbaAgent")
@Description("向 DBA 数据库专家提问。适用于 SQL 查询、慢查询分析、表结构查看、数据统计等数据库运维问题。参数：question - 问题, instanceId - 目标数据库实例ID")
public Function<DbaAgentRequest, AgentResponse> askDbaAgent(ChatModel chatModel) {
    ChatClient dbaClient = ChatClient.builder(chatModel)
            .defaultSystem(DBA_SYSTEM_PROMPT)
            .defaultToolNames("schemaReaderTool", "sqlExecutorTool")
            .build();
    return request -> {
        String userPrompt = "目标数据库实例: %s\n用户问题: %s"
                .formatted(request.instanceId(), request.question());
        String answer = dbaClient.prompt()
                .user(userPrompt)
                .call()
                .content();
        return new AgentResponse(answer);
    };
}
```

和 askRagAgent 结构一样，区别在于：
- 它挂载的是 `schemaReaderTool` 和 `sqlExecutorTool`
- 它接收的请求多了一个 `instanceId`
- 它把 instanceId 注入到 user prompt 里，这样子 Agent 的大模型在调用 schemaReaderTool 和 sqlExecutorTool 时，就知道该传哪个 instanceId

```java
String userPrompt = "目标数据库实例: %s\n用户问题: %s"
        .formatted(request.instanceId(), request.question());
```
这里用了 Java 15 的 `.formatted()` 方法（和 `String.format()` 功能一样，但写法更简洁）。它把 instanceId 放在问题前面，引导大模型在调用工具时使用正确的实例。

### System Prompt 常量

```java
private static final String RAG_SYSTEM_PROMPT = """
        你是知识库检索专家。你的任务是根据用户问题，使用 milvusSearchTool 在向量知识库中检索相关文档片段，
        然后基于检索到的内容，准确、完整地回答用户问题。
        如果检索结果为空或不相关，请如实告知用户。
        回答时引用关键知识点，不要编造文档中没有的内容。
        """;
```

这个字符串告诉大模型它的角色和行为准则。注意"不要编造文档中没有的内容"这类约束——这是 RAG 场景下非常重要的提示工程技巧。

DBA 的 System Prompt 更详细，因为 DBA 场景更复杂，需要引导大模型按"读表结构 → 写 SQL → 执行 → 分析结果"的流程工作。

### 你应该怎么理解？

以前 RagAgent 长这样：
```java
// 旧版：Java 代码决定一切
public String answer(String message) {
    ToolExecutionResult result = milvusSearchTool.apply(...);  // 手动调用
    String context = formatContext(result);
    return chatClient.call("基于以下内容回答：" + context + "\n问题：" + message);
}
```

现在 askRagAgent 长这样：
```java
// 新版：大模型自己决定要不要调工具
return request -> {
    String answer = ragClient.prompt()
            .user(request.question())
            .call()    // 大模型自己决定调不调 milvusSearchTool
            .content();
    return new AgentResponse(answer);
};
```

代码量大幅减少，而且大模型有了自主权——如果它觉得这个问题不需要检索向量库（比如用户只是在打招呼），它可以直接回答。

---

## 4.6 `TargetDataSourceRegistry`

职责：
- 管理所有目标数据源的连接池
- 启动时从数据库加载
- 运行时支持增删（热加载）
- 根据 instanceId 返回对应的 JdbcTemplate

### init 方法

```java
@PostConstruct
public void init() {
    List<TargetDatasource> list = mapper.selectList(
            new LambdaQueryWrapper<TargetDatasource>().eq(TargetDatasource::getStatus, 1));
    list.forEach(this::registerDataSource);
    log.info("Loaded {} target datasource(s): {}", registry.size(), registry.keySet());
}
```

`@PostConstruct` 表示 Bean 创建完成后自动执行。它做了三件事：
1. 从 target_datasource 表查出所有 status=1（启用）的记录
2. 对每条记录调用 `registerDataSource()` 创建连接池
3. 打日志记录加载了多少个数据源

`LambdaQueryWrapper` 是 MyBatis-Plus 的查询条件构建器，`.eq(TargetDatasource::getStatus, 1)` 相当于 SQL 的 `WHERE status = 1`。

### registerDataSource 方法

```java
private void registerDataSource(TargetDatasource config) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(config.getUrl());
    ds.setUsername(config.getUsername());
    ds.setPassword(config.getPassword());
    ds.setMaximumPoolSize(5);
    ds.setMinimumIdle(1);
    ds.setConnectionTimeout(5000);
    ds.setReadOnly(true);
    ds.setPoolName("target-" + config.getInstanceId());
    dataSources.put(config.getInstanceId(), ds);
    registry.put(config.getInstanceId(), new JdbcTemplate(ds));
}
```

逐行解释：
- `HikariDataSource`：HikariCP 连接池，Spring Boot 默认的连接池。这里手动创建一个新的连接池给目标数据库用
- `setMaximumPoolSize(5)`：最多 5 个连接。目标库只做查询，不需要太多
- `setMinimumIdle(1)`：空闲时至少保留 1 个连接
- `setConnectionTimeout(5000)`：5 秒连不上就超时
- `setReadOnly(true)`：**强制只读**。非常重要的安全措施——目标数据库是用户的生产库，绝对不能写
- `setPoolName("target-" + ...)`：给连接池起个名，方便在监控日志里区分
- `dataSources.put(...)`：把 HikariDataSource 存起来，方便后续关闭连接池
- `registry.put(...)`：把 JdbcTemplate 存起来，供 Tool 使用

### addDataSource / removeDataSource 方法

```java
public void addDataSource(TargetDatasource config) {
    removeDataSource(config.getInstanceId());   // 先移除旧的（如果有）
    registerDataSource(config);                  // 再注册新的
}

public void removeDataSource(String instanceId) {
    registry.remove(instanceId);
    HikariDataSource ds = dataSources.remove(instanceId);
    if (ds != null) {
        ds.close();   // 关闭连接池，释放数据库连接
    }
}
```

注意 `ds.close()` 很重要。如果不关闭，数据库连接会一直占着不放。

### getJdbcTemplate 方法

```java
public JdbcTemplate getJdbcTemplate(String instanceId) {
    JdbcTemplate jt = registry.get(instanceId);
    if (jt == null) {
        throw new IllegalArgumentException(
                "未知的数据库实例: " + instanceId + ", 可用实例: " + registry.keySet());
    }
    return jt;
}
```

根据 instanceId 返回对应的 JdbcTemplate。如果 instanceId 不存在，抛异常——这个异常会被 Tool 的 try-catch 捕获，变成 `ToolExecutionResult.failure()`，大模型会收到错误信息。

### 两个 ConcurrentHashMap

```java
private final ConcurrentHashMap<String, JdbcTemplate> registry = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
```

为什么用 `ConcurrentHashMap` 而不是 `HashMap`？因为 REST API 可能并发操作（一边在添加数据源，一边在查询），`ConcurrentHashMap` 是线程安全的。

为什么有两个 Map？
- `registry`：给外部用的，Key 是 instanceId，Value 是 JdbcTemplate（用来执行 SQL 的）
- `dataSources`：给内部用的，Key 是 instanceId，Value 是 HikariDataSource（用来关闭连接池的）

JdbcTemplate 没有 close 方法，你不能通过它关闭连接池。必须持有 HikariDataSource 的引用才能关闭。

---

## 4.7 `DataSourceController`

职责：
- 提供 REST API 给用户管理目标数据源

### add 方法

```java
@PostMapping
public BaseResponse<TargetDatasource> add(@Valid @RequestBody DataSourceRequest request) {
    TargetDatasource entity = new TargetDatasource();
    entity.setInstanceId(request.getInstanceId());
    entity.setName(request.getName());
    entity.setUrl(request.getUrl());
    entity.setUsername(request.getUsername());
    entity.setPassword(request.getPassword());
    entity.setStatus(1);
    mapper.insert(entity);         // 1. 写入数据库
    registry.addDataSource(entity); // 2. 创建连接池并加入内存
    return ResultUtils.success(entity);
}
```

注意做了两件事：
1. `mapper.insert(entity)`：持久化到数据库，这样下次重启也能加载
2. `registry.addDataSource(entity)`：在内存中创建连接池，马上可用

### list 方法

```java
@GetMapping
public BaseResponse<List<TargetDatasource>> list() {
    List<TargetDatasource> list = mapper.selectList(null);
    list.forEach(ds -> ds.setPassword("******"));  // 脱敏
    return ResultUtils.success(list);
}
```

`ds.setPassword("******")`：列表接口不返回真实密码。这是基本的安全意识——密码不能通过 API 暴露出去。

### testConnection 方法

```java
@PostMapping("/{instanceId}/test")
public BaseResponse<String> testConnection(@PathVariable String instanceId) {
    try {
        registry.getJdbcTemplate(instanceId).queryForObject("SELECT 1", Integer.class);
        return ResultUtils.success("连接成功");
    } catch (Exception e) {
        return ResultUtils.success("连接失败: " + e.getMessage());
    }
}
```

`SELECT 1` 是最简单的测试查询，所有 MySQL 都能执行。如果能执行成功，说明连接没问题。

---

## 4.8 `SchemaReaderTool`（重构后）

职责：
- 读取指定数据库实例中某张表的字段结构

### 和旧版的核心区别

旧版注入的是项目自身的 JdbcTemplate：
```java
// 旧版
public Function<SchemaReadRequest, ToolExecutionResult> schemaReaderTool(JdbcTemplate jdbcTemplate) {
```

新版注入的是 TargetDataSourceRegistry：
```java
// 新版
public Function<SchemaReadRequest, ToolExecutionResult> schemaReaderTool(
        TargetDataSourceRegistry dsRegistry) {
```

### 关键代码段

```java
String instanceId = request == null ? null : request.instanceId();
if (!StringUtils.hasText(instanceId)) {
    return ToolExecutionResult.failure(
            "instanceId 不能为空，可用实例: " + dsRegistry.getAvailableInstanceIds());
}
```

如果大模型没传 instanceId，返回一个友好的错误信息，还告诉它有哪些可用实例。大模型看到后可以自动修正参数重试。

```java
List<Map<String, Object>> rows = dsRegistry.getJdbcTemplate(instanceId).queryForList(...)
```

根据 instanceId 从注册中心拿到对应的 JdbcTemplate，再查 information_schema 拿表结构。

### try-catch 包裹

```java
return request -> {
    try {
        // ... 正常逻辑
    } catch (IllegalArgumentException ex) {
        return ToolExecutionResult.failure(ex.getMessage());
    } catch (Exception ex) {
        return ToolExecutionResult.failure("读取表结构失败: " + ex.getMessage());
    }
};
```

**所有异常都捕获后变成 failure 结果**，而不是直接抛出。为什么？
- 如果抛异常，整个 Function Calling 流程会中断
- 返回 failure，大模型收到后可以自己分析错误原因，换个参数重试

比如大模型传了一个不存在的表名，收到"未找到表结构: xxx"后，它可能会先调用 schemaReaderTool 查 `information_schema.tables` 看看有哪些表，再重新指定正确的表名。这就是"自我纠错"能力。

---

## 4.9 `SqlExecutorTool`（重构后）

和 SchemaReaderTool 的改动思路完全一样：
- 注入 `TargetDataSourceRegistry` 替代 `JdbcTemplate`
- 根据 `instanceId` 获取对应连接
- 所有异常都包裹成 `ToolExecutionResult.failure()`

多了一个安全校验：
```java
securitySandboxService.validateReadOnlySql(rawSql);
```
这一步会用 Druid AST 解析 SQL，如果发现是 DELETE / UPDATE / DROP 等写操作，直接拒绝。这个逻辑和旧版一样，没变。

还有自动加 LIMIT 的逻辑：
```java
private String applyDefaultLimit(String sql, int maxLimit) {
    String normalized = sql.toLowerCase();
    if (!normalized.startsWith("select") || normalized.contains(" limit ")) {
        return sql;
    }
    return sql + " limit " + maxLimit;
}
```
如果大模型生成的 SELECT 没有 LIMIT 子句，自动加一个 `LIMIT 100`，防止查出几十万条数据。

---

## 4.10 `MilvusSearchTool`（重构后）

和旧版的改动：
1. 增加了 try-catch 包裹
2. `SearchRequest` API 从 `SearchRequest.query(kw).withTopK(n)` 变成了 `SearchRequest.builder().query(kw).topK(n).build()`
3. `document.getContent()` 改成了 `document.getText()`（Spring AI 1.0.0 改名了）

```java
List<Document> documents = vectorStore.similaritySearch(
        SearchRequest.builder()
                .query(request.keyword())
                .topK(topK)
                .build()
);
```

这是 Spring AI 1.0.0 的新 API，用 Builder 模式构建搜索请求，比旧版更清晰。

---

## 4.11 `ToolPayloads`

职责：
- 放所有 Tool 和 Agent 通信用的数据结构

新增的三个 Record：
```java
public record RagAgentRequest(String question) {}
public record DbaAgentRequest(String question, String instanceId) {}
public record AgentResponse(String answer) {}
```

这三个是给 SubAgentFunctionConfig 用的。大模型调用 `askRagAgent` 时，Spring AI 会把大模型传的参数反序列化成 `RagAgentRequest`；函数执行完返回 `AgentResponse`，Spring AI 再序列化成 JSON 发回给大模型。

变更的两个 Record：
```java
// 旧版
public record SchemaReadRequest(String tableName) {}
public record SqlExecutionRequest(String sql) {}

// 新版
public record SchemaReadRequest(String instanceId, String tableName) {}
public record SqlExecutionRequest(String instanceId, String sql) {}
```

都加了 `instanceId`，因为现在支持多个数据库实例了。

---

## 4.12 `ChatRequest` / `ChatResponse`

### ChatRequest
```java
@Data
public class ChatRequest {
    @NotBlank(message = "message 不能为空")
    private String message;

    private String conversationId;  // 新增
}
```

`conversationId` 没有 `@NotBlank`，因为它是可选的。第一次聊天不传，系统自动生成。

### ChatResponse
```java
@Data
@Builder
public class ChatResponse {
    private String conversationId;  // 新增
    private String answer;
    // 删除了旧版的 target 和 reason
}
```

旧版返回 `target`（RAG 还是 DBA）和 `reason`（路由原因），现在不需要了——Supervisor 模式下调度细节不暴露给用户。

---

## 4.13 `DataSourceRequest`

新增的 DTO：
```java
@Data
public class DataSourceRequest {
    @NotBlank(message = "instanceId 不能为空")
    private String instanceId;

    @NotBlank(message = "name 不能为空")
    private String name;

    @NotBlank(message = "url 不能为空")
    private String url;

    @NotBlank(message = "username 不能为空")
    private String username;

    @NotBlank(message = "password 不能为空")
    private String password;
}
```

标准的请求体 DTO。所有字段都加了 `@NotBlank` 校验。

---

## 4.14 `TargetDatasource`（实体）

```java
@Data
@TableName("target_datasource")
public class TargetDatasource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String instanceId;
    private String name;
    private String url;
    private String username;
    private String password;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

注意 `@TableId(type = IdType.AUTO)` 用的是自增主键，不是雪花 ID（`ASSIGN_ID`）。因为 target_datasource 表的 id 用的是 `AUTO_INCREMENT`，不需要在 Java 层生成。

---

# 5. 文档上传链路（和上个版本变化不大）

以下这些类和上个版本基本没有变化，如果你之前已经读过，可以跳过。

---

## 5.1 `DocumentController`

职责：
- 暴露文档上传接口
- 不自己做上传和入库，只负责把文件交给 `DocumentUploadService`

核心方法：
```java
@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public BaseResponse<DocumentUploadResponse> upload(@RequestPart("file") MultipartFile file) {
    return ResultUtils.success(documentUploadService.uploadAndDispatch(file));
}
```

### 你应该怎么理解？
- `@RequestPart("file")`：从 multipart 请求中拿文件部分
- `MultipartFile`：Spring 封装的上传文件对象
- `BaseResponse<DocumentUploadResponse>`：统一响应包装
- `ResultUtils.success(...)`：统一构造成功响应

这个类很薄，符合"Controller 只做入口分发"的设计。

---

## 5.2 `DocumentUploadService`

职责：
1. 校验上传文件
2. 把文件先存为临时文件
3. 上传到 OSS
4. 创建 `knowledge_document` 记录
5. 发送 MQ 消息，让异步消费者继续处理
6. 删除临时文件

你可以把它理解成"上传链路总协调器"。

### 关键方法：`uploadAndDispatch(MultipartFile file)`

处理顺序：
1. `ThrowUtils.throwIf(...)` 校验文件是否为空
2. `Files.createTempFile(...)` 创建临时文件
3. `file.transferTo(tempFile)` 把上传文件落到临时文件
4. `objectStorageService.upload(...)` 上传到 OSS
5. 创建 `KnowledgeDocument`
6. 组装 `DocumentIngestMessage`
7. `rabbitTemplate.convertAndSend(...)` 发 MQ
8. finally 里删除临时文件

### 这里体现了什么工程思路？
- 本地临时文件只是"中转"，不是长期存储
- 真正长期存储是 OSS
- 解析和切分这些耗时操作不在上传接口同步完成，而是异步执行

---

## 5.3 `ObjectStorageService` / `AliyunOssStorageService`

`ObjectStorageService` 是接口，`AliyunOssStorageService` 是阿里云 OSS 实现。

方法：
- `upload(...)`
- `download(...)`
- `getObjectUrl(...)`

这一步非常重要，因为它把"业务"和"具体云厂商 SDK"隔开了。业务代码只认接口，具体用阿里云还是腾讯云，是实现类的事情。

---

## 5.4 `DocumentMqListener`

职责：
- 监听 RabbitMQ 中的文档解析消息
- 收到消息后调用 `DocumentIngestService`

核心方法：
```java
@RabbitListener(queues = MqConstants.DOCUMENT_QUEUE)
public void onMessage(DocumentIngestMessage message) {
    documentIngestService.ingest(message);
}
```

上传接口只负责"发起任务"，真正解析文档的是 MQ 消费者这条异步链路。

---

## 5.5 `DocumentIngestService`

职责：
1. 根据 `documentId` 查数据库记录
2. 从 OSS 下载文件流
3. 更新文档状态为 `PROCESSING`
4. 交给 Tika 解析
5. 交给 splitter 切分
6. 写入 Milvus
7. 写入 `knowledge_segment`
8. 成功则更新状态为 `COMPLETED`
9. 失败则更新状态为 `FAILED`

你可以把它理解成"文档异步入库总流程"的真正核心。

---

## 5.6 `TikaDocumentParsingService`

职责：用 Apache Tika 统一解析不同文档格式（txt、md、pdf、doc、docx）。

为什么用 Tika？因为它很适合做"统一入口解析"，不用你自己判断到底是 pdf 还是 docx，它会自动选择合适解析器。

---

## 5.7 `DefaultDocumentSplittingService`

职责：把长文档切成适合向量检索的小段。

实现思路：
1. 优先使用 `TokenTextSplitter`
2. 如果失败，回退到滑动窗口切分

这种"双轨方案"体现的是：既追求效果，又保留兜底可控性。

---

# 6. 异常体系（和上个版本一样）

## 6.1 `ErrorCode`
错误码定义，比如 `SUCCESS(0, "ok")`、`PARAMS_ERROR(40000, "请求参数错误")`。

## 6.2 `BusinessException`
表示"可预期的业务错误"，带有 `code` 和 `message`。

## 6.3 `ThrowUtils`
快速根据条件抛业务异常：
```java
ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");
```

## 6.4 `GlobalExceptionHandler`
全局捕获异常并转成统一响应。处理三类主情况：
1. `BusinessException`
2. 参数校验异常
3. 普通 `Exception`

---

# 7. 数据库实体

## `KnowledgeDocument`
存文档记录：fileName、fileUrl、storageProvider、objectKey、contentType、status、failureReason。

## `KnowledgeSegment`
存文档切分后的片段：documentId、segmentIndex、content、vectorId。

## `SlowQueryLog`
存慢查询日志：sqlText、executionTimeMs、user、happenTime。

## `TargetDatasource`（新增）
存目标数据源配置：instanceId、name、url、username、password、status。

---

# 8. 你现在最容易陌生的新点

## 8.1 `ChatClient.builder()` 链式构建
Spring AI 1.0.0 的核心 API。你可以把它理解成：
> 给大模型配置"你是谁"、"你能用什么工具"、"你能记住什么"，然后每次只管喂用户消息就行了。

## 8.2 `.defaultFunctions("beanName")`
告诉 ChatClient "你可以调用这些 Spring Bean"。Spring AI 会自动：
1. 把 Bean 的 `@Description` 和参数类型告诉大模型
2. 大模型返回 function_call 时，自动调用对应 Bean
3. 把 Bean 的返回值发回给大模型

## 8.3 `Function<Request, Response>` Bean
Spring AI 的 Function Calling 要求你把工具注册成 `Function<输入, 输出>` 类型的 Bean。输入和输出都是普通 Java 对象（Record 就行），Spring AI 自动处理 JSON 序列化/反序列化。

## 8.4 `MessageWindowChatMemory`
滑动窗口记忆。自动维护每个 conversationId 的历史消息，超过上限自动丢弃最早的。

## 8.5 `@PostConstruct`
标注在方法上，表示"这个 Bean 被 Spring 创建完成后，自动执行这个方法"。TargetDataSourceRegistry 用它来在启动时加载数据源。

## 8.6 `ConcurrentHashMap`
线程安全的 HashMap。在可能有并发访问的场景（比如一边在处理 REST 请求添加数据源，一边有聊天请求在查 JdbcTemplate），用它代替普通 HashMap。

## 8.7 `HikariDataSource`
HikariCP 连接池。Spring Boot 默认就用这个连接池。这里我们手动创建它，是因为目标数据源不走 Spring Boot 的自动配置（那个只管控制面元数据库），需要自己建连接池。

## 8.8 `SearchRequest.builder()`
Spring AI 1.0.0 用 Builder 模式替代了旧的静态工厂方法。旧版是 `SearchRequest.query(kw).withTopK(n)`，新版是 `SearchRequest.builder().query(kw).topK(n).build()`。

## 8.9 `document.getText()` vs `document.getContent()`
Spring AI 1.0.0 把 `getContent()` 改名成了 `getText()`，因为 "text" 比 "content" 更明确（content 可以是图片、音频等）。旧的 `getContent()` 还能用但已标记废弃。

---

# 9. 给你现在的学习建议

你现在最应该重点建立的，不是"会不会用某个 SDK"，而是下面这几个工程意识：

1. **真 Agent vs 假 Agent**：业务层不直接调用 Tool，让大模型通过 Function Calling 自主决策
2. **Supervisor 模式**：一个主管 Agent 调度多个专家 Agent，支持多次调用、混合问题、自我纠错
3. **对话记忆**：通过 conversationId 关联历史消息，支持多轮追问
4. **控制面与数据面隔离**：元数据库和目标库物理分离，目标库动态管理
5. **Tool 异常优雅处理**：返回失败结果而非抛异常，让大模型自我纠错
6. **文件不应该长期依赖应用本地磁盘**
7. **解析、切分、向量化这些重操作要异步化**
8. **异常和响应要统一风格**

---

# 10. 你接下来如果想让我带你精读，最推荐这三个类

1. `SupervisorAgent` — 看它怎么用 50 行代码干了以前三个 Agent 的活
2. `SubAgentFunctionConfig` — 看 Function Calling 到底怎么把逻辑"声明式"地挂到大模型上
3. `TargetDataSourceRegistry` — 看动态数据源的生命周期管理（创建连接池、热加载、关闭连接池）

如果你愿意，下一条你直接说：
- `先讲 SupervisorAgent`
- `先讲 SubAgentFunctionConfig`
- `先讲 TargetDataSourceRegistry`

我就只盯着那个模块，继续像之前那样，一行一行给你讲。
