# DataPilot 学习导读

这份文档不是运行手册，而是**带你读源码**的学习文件。

我会尽量按一个刚从传统 `controller-service-mapper` 三层项目过渡到这个项目的人能接受的方式来讲。你可以把它理解成：

- 先告诉你每个目录是干什么的
- 再告诉你每个核心类的职责
- 再告诉你每个核心方法在做什么
- 最后把一些你可能陌生的语法糖、Spring 注解、AI 项目里新增的概念解释清楚

---

## 1. 先建立整体认识：这个项目到底在干什么？

如果用一句话概括：

> DataPilot 是一个“数据库智能运维 Copilot”，用户发一个问题进来，系统会先判断你是在问“知识/架构类问题”，还是在问“数据库/SQL 类问题”，然后走不同处理链路。

它主要有两条业务链路：

### 第一条：RAG 问答链路
适合问：
- 这个系统的架构是什么？
- 文档导入流程怎么走？
- 某个模块职责是什么？

处理方式是：
1. 先去向量库里检索相关文档片段
2. 把检索结果喂给大模型
3. 让大模型基于这些片段来回答

### 第二条：DBA 问答链路
适合问：
- 最慢的 SQL 是什么？
- 按用户统计慢查询情况
- 某张表能怎么查

处理方式是：
1. 先读数据库表结构
2. 让大模型生成 SQL
3. SQL 不能直接执行，必须先过**安全沙盒**
4. 只有只读 SQL 才允许执行
5. 执行后再把结果交给大模型总结

### 还有一条：文档导入链路
适合做：
- 把一个本地 txt 文档导入知识库
- 切分文档，写入 MySQL 和 Milvus
- 后续 RAG 问答就能检索到这些片段

---

## 2. 你最应该先建立的思维变化

你之前更熟悉的是：

- Controller 接请求
- Service 处理业务
- Mapper 操作数据库

这个项目**并没有推翻这个模式**，它只是多加了一层“AI 调度逻辑”。

你可以这样理解：

- `controller`：还是入口
- `service`：还是业务协调
- `mapper`：还是数据库访问
- `agent`：相当于“会调用大模型的一层业务角色”
- `tools`：相当于“给大模型调用的受控工具”
- `mq`：异步消费链路

所以本质上，你还是在看一个 Spring Boot 项目，只是它把“AI 调用”拆成了多个角色。

---

## 3. 项目目录说明

项目主代码目录：

```text
src/main/java/com/bloodstar/fluxragcompute
├── agent
├── config
├── constant
├── controller
├── dto
├── entity
├── exception
├── mapper
├── mq
├── service
└── tools
```

下面我按“职责”讲。

### 3.1 `controller`
对外暴露 HTTP 接口。

这里有两个入口：
- `CopilotController`：聊天问答接口
- `DocumentController`：文档导入接口

### 3.2 `service`
放业务协调逻辑。

这里最重要的三个类：
- `CopilotService`：统一调度聊天请求
- `SecuritySandboxService`：做 SQL 安全校验
- `DocumentIngestService`：文档导入核心逻辑

### 3.3 `agent`
这是这个项目相对“新”的地方。

可以把它理解成：
- `RouterAgent`：分诊台
- `RagAgent`：知识库问答专家
- `DbaAgent`：数据库分析专家

它们本质上不是传统 MVC 中固定的一层，而是**封装了大模型调用流程的业务角色**。

### 3.4 `tools`
这是给 Agent 用的“工具”。

你可以把它理解成：
- 大模型本身只会生成文字
- 但你想让它真正去查 Milvus、查数据库、执行 SQL
- 那就必须给它一组受控工具

这里有三个工具：
- `MilvusSearchTool`：查向量库
- `SchemaReaderTool`：查表结构
- `SqlExecutorTool`：执行只读 SQL

### 3.5 `entity`
数据库实体类，对应三张表：
- `knowledge_document`
- `knowledge_segment`
- `slow_query_log`

### 3.6 `mapper`
MyBatis-Plus 的 Mapper 接口。

它们都继承了 `BaseMapper<T>`，所以不用自己写 XML，也能直接调用常见 CRUD。

### 3.7 `mq`
放 RabbitMQ 消费者。

这里的 `DocumentMqListener` 就是文档导入消息的监听器。

### 3.8 `config`
放配置类。

目前主要有：
- `RabbitMqConfig`：声明队列、交换机、绑定、消息转换器
- `MybatisPlusConfig`：配置 MyBatis-Plus 插件

### 3.9 `dto`
放请求响应对象、工具载荷对象。

这个目录的作用和你熟悉的项目一样，就是：
- Controller 收参
- Service / Agent / Tool 之间传对象
- 返回统一响应

### 3.10 `exception`
放异常和全局异常处理。

### 3.11 `constant`
放常量类，比如 MQ 的交换机、队列名、路由键。

---

## 4. 整个项目最重要的两条主流程

先看流程，再看代码，会容易很多。

## 4.1 聊天问答流程

```text
用户 -> /api/chat -> CopilotController
     -> CopilotService
     -> RouterAgent 判断走 RAG 还是 DBA
     -> RAG: RagAgent -> MilvusSearchTool -> 向量库检索 -> 大模型回答
     -> DBA: DbaAgent -> SchemaReaderTool -> SqlExecutorTool -> SecuritySandboxService -> 数据库 -> 大模型总结
```

### 这个流程里谁最核心？
- `CopilotController`
- `CopilotService`
- `RouterAgent`
- `RagAgent`
- `DbaAgent`

## 4.2 文档导入流程

```text
用户 -> /api/documents/ingest -> DocumentController
     -> RabbitMQ
     -> DocumentMqListener
     -> DocumentIngestService
     -> 读文件 / 切片 / 写 Milvus / 写 MySQL
```

### 这个流程里谁最核心？
- `DocumentController`
- `RabbitMqConfig`
- `DocumentMqListener`
- `DocumentIngestService`

---

# 5. 核心类详解

下面进入重点。我会按“你最应该先读哪些类”的顺序来讲。

---

## 5.1 启动类：`DataPilotApplication`

文件：`src/main/java/com/bloodstar/fluxragcompute/DataPilotApplication.java`

```java
@MapperScan("com.bloodstar.fluxragcompute.mapper")
@SpringBootApplication
public class DataPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataPilotApplication.class, args);
    }
}
```

## 它的职责
这是整个 Spring Boot 项目的启动入口。

## 逐句解释

### `@MapperScan("com.bloodstar.fluxragcompute.mapper")`
意思是：
- 扫描这个包下的 Mapper 接口
- 让 MyBatis 把它们注册成 Bean
- 这样你后面才能直接注入 `KnowledgeDocumentMapper` 之类的对象

如果没有它，你的 Mapper 可能不会被 Spring 托管。

### `@SpringBootApplication`
这是 Spring Boot 最常见的启动注解。

它本质上是三个注解的组合：
- `@Configuration`：说明这是配置类
- `@EnableAutoConfiguration`：开启自动配置
- `@ComponentScan`：扫描当前包及子包下的组件

你可以把它简单理解成：
> “从这里开始，整个 Spring Boot 应用启动起来。”

### `public static void main(String[] args)`
Java 程序入口，和普通 Java 程序一样。

### `SpringApplication.run(...)`
真正启动 Spring 容器，加载 Bean，启动内嵌 Tomcat。

---

## 5.2 聊天接口入口：`CopilotController`

文件：`src/main/java/com/bloodstar/fluxragcompute/controller/CopilotController.java`

```java
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CopilotController {

    private final CopilotService copilotService;

    @PostMapping("/chat")
    public ApiResponseResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(copilotService.chat(request.getMessage()));
    }
}
```

## 它的职责
接收前端/调用方的聊天请求，然后把请求交给 `CopilotService`。

它本身**不处理复杂业务**，这点和传统三层项目是一样的。

## 逐句解释

### `@RestController`
等价于：
- `@Controller`
- `@ResponseBody`

意思是：
> 这是一个控制器，并且方法返回值会直接序列化成 JSON 响应。

### `@RequestMapping("/api")`
给整个类加一个统一前缀。

所以这个类里的接口最终路径都会以 `/api` 开头。

### `@RequiredArgsConstructor`
这是 **Lombok** 语法糖。

它会自动帮你生成一个“包含所有 `final` 字段”的构造方法。

比如这句：

```java
private final CopilotService copilotService;
```

会自动生成类似：

```java
public CopilotController(CopilotService copilotService) {
    this.copilotService = copilotService;
}
```

这样 Spring 就可以通过构造器注入 Bean。

### `private final CopilotService copilotService;`
控制器依赖业务层。

这里用 `final` 有两个好处：
1. 表明这个依赖初始化后不会变
2. 配合 `@RequiredArgsConstructor` 自动注入

### `@PostMapping("/chat")`
定义接口：
- 请求方式：POST
- 路径：`/api/chat`

### `public ApiResponseResponse<ChatResponse> chat(...)`
这个返回值你要重点理解。

`ApiResponseResponse<ChatResponse>` 的意思是：
- 最外层是统一响应包装
- 里面真正的数据是 `ChatResponse`

比如接口返回 JSON 时，大概会长这样：

```json
{
  "success": true,
  "message": "OK",
  "data": {
    "target": "RAG",
    "reason": "这是知识类问题",
    "answer": "..."
  }
}
```

### `@Valid`
开启参数校验。

它会去检查 `ChatRequest` 里的注解，比如 `@NotBlank`。

### `@RequestBody ChatRequest request`
意思是：
- 从请求体里读取 JSON
- 反序列化成 `ChatRequest` 对象

比如传入：

```json
{"message":"帮我找出最慢的SQL"}
```

就会映射到：

```java
request.getMessage()
```

### `ApiResponse.ok(...)`
调用统一响应工具方法，包装成功结果。

### `copilotService.chat(request.getMessage())`
真正业务处理开始的地方。

---

## 5.3 聊天总调度：`CopilotService`

文件：`src/main/java/com/bloodstar/fluxragcompute/service/CopilotService.java`

```java
@Service
@RequiredArgsConstructor
public class CopilotService {

    private final RouterAgent routerAgent;
    private final RagAgent ragAgent;
    private final DbaAgent dbaAgent;

    public ChatResponse chat(String message) {
        RouteDecision decision = routerAgent.route(message);
        String target = decision.normalizedTarget();
        String answer = "DBA".equals(target) ? dbaAgent.answer(message) : ragAgent.answer(message);
        return ChatResponse.builder()
                .target(target)
                .reason(decision.getReason())
                .answer(answer)
                .build();
    }
}
```

## 它的职责
这是聊天业务的“总调度中心”。

如果你想找“整个系统聊天流程从哪里开始真正分流”，就是这里。

## 逐句解释

### `@Service`
说明这是业务层 Bean。

### 三个依赖

```java
private final RouterAgent routerAgent;
private final RagAgent ragAgent;
private final DbaAgent dbaAgent;
```

含义：
- `RouterAgent`：先判断问题属于哪一类
- `RagAgent`：处理知识问答
- `DbaAgent`：处理数据库问答

### `RouteDecision decision = routerAgent.route(message);`
先让路由 Agent 判断这句话应该走哪条链路。

### `String target = decision.normalizedTarget();`
把路由结果规范化。

比如：
- `rag`
- ` RAG `
- `Rag`

都可以最终统一成 `RAG`。

### 三元表达式

```java
String answer = "DBA".equals(target) ? dbaAgent.answer(message) : ragAgent.answer(message);
```

这是 Java 的**三元运算符**：

```java
条件 ? 条件成立时的值 : 条件不成立时的值
```

等价于：

```java
String answer;
if ("DBA".equals(target)) {
    answer = dbaAgent.answer(message);
} else {
    answer = ragAgent.answer(message);
}
```

### `ChatResponse.builder()`
这是 Lombok `@Builder` 生成的构建器写法。

相比直接 `new ChatResponse()` 再 set，Builder 更适合字段较多的对象。

等价思想是：

```java
ChatResponse response = new ChatResponse();
response.setTarget(target);
response.setReason(decision.getReason());
response.setAnswer(answer);
```

只是 Builder 写法更清晰。

---

## 5.4 路由 Agent：`RouterAgent`

文件：`src/main/java/com/bloodstar/fluxragcompute/agent/RouterAgent.java`

这是你第一次接触会觉得“新”的类，但其实只要抓住职责就不难。

## 它的职责
> 它不回答问题，它只负责“分流”。

也就是判断：
- 这句话该走 `RAG`
- 还是该走 `DBA`

## 核心方法：`route(String question)`

```java
public RouteDecision route(String question) {
    String prompt = """
            你是 DataPilot 的 Router Agent。
            你的职责只有一个：把用户问题路由到 RAG 或 DBA。
            ...
            """.formatted(question);
    try {
        String raw = chatClient.call(prompt);
        RouteDecision decision = objectMapper.readValue(extractJson(raw), RouteDecision.class);
        if (!StringUtils.hasText(decision.getTarget())) {
            return fallback(question);
        }
        return decision;
    } catch (Exception ex) {
        log.warn("RouterAgent parse failed, fallback to heuristic routing", ex);
        return fallback(question);
    }
}
```

## 逐句解释

### `private final ChatClient chatClient;`
这里的 `ChatClient` 是 Spring AI 提供的聊天客户端。

你可以简单理解成：
> 它是 Java 代码调用大模型的入口对象。

### `private final ObjectMapper objectMapper;`
这是 Jackson 的 JSON 工具类。

用途：
- Java 对象转 JSON
- JSON 转 Java 对象

### Java 文本块 `""" ... """`

```java
String prompt = """
    ...
    """.formatted(question);
```

这是 Java 的**文本块**语法，用来写多行字符串。

好处是：
- 不用到处写 `\n`
- Prompt 看起来更清晰

### `.formatted(question)`
这是字符串格式化。

文本块里有 `%s`，这里就会把 `question` 填进去。

相当于：

```java
String.format(template, question)
```

### `chatClient.call(prompt)`
调用大模型。

返回的是模型输出的原始文本，比如：

```json
{"target":"DBA","reason":"用户在问慢查询"}
```

### `objectMapper.readValue(..., RouteDecision.class)`
把 JSON 字符串转成 Java 对象。

### `extractJson(raw)`
为什么还要专门提取 JSON？

因为大模型有时不老实，可能返回：

```text
```json
{"target":"RAG","reason":"..."}
```
```

或者前后多带一些说明文字。

所以 `extractJson()` 的作用就是：
- 去掉代码块包裹
- 截出真正的 JSON 部分

### `StringUtils.hasText(...)`
Spring 工具方法。

和“非空”不完全一样，它还会判断：
- `null`
- `""`
- 全空格字符串

都算无效。

### `fallback(question)`
这是一个兜底策略。

意思是：
- 如果模型返回格式不对
- 或者 JSON 解析失败
- 或者压根没返回 target

就不要让整个系统挂掉，而是改用**关键词规则**去判断。

这很像你做传统项目时写的降级逻辑。

---

## 5.5 路由兜底：`fallback(String question)`

```java
private RouteDecision fallback(String question) {
    String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
    boolean dba = lower.contains("sql")
            || lower.contains("慢查询")
            || lower.contains("数据库")
            || lower.contains("表结构")
            || lower.contains("slow_query_log")
            || lower.contains("execution_time")
            || lower.contains("统计");
    RouteDecision decision = new RouteDecision();
    decision.setTarget(dba ? "DBA" : "RAG");
    decision.setReason(dba ? "命中数据库分析关键词，按 DBA 链路处理" : "默认走知识问答链路");
    return decision;
}
```

## 你应该学到什么？
这段代码体现了一个很典型的工程思想：

> 不能把所有正确性都赌在大模型身上。

这里即使模型失效，也能依靠关键字规则继续工作。

### `question == null ? "" : ...`
这又是三元表达式。

意思是：
- 如果 question 是 null，用空字符串
- 否则转小写

### `toLowerCase(Locale.ROOT)`
统一转小写，避免大小写判断问题。

### 一串 `contains(...)`
就是最基础的关键词命中策略。

### `decision.setTarget(dba ? "DBA" : "RAG")`
又是一个三元表达式。

意思是：
- 如果命中数据库关键词，就走 DBA
- 否则走 RAG

---

## 5.6 RAG Agent：`RagAgent`

文件：`src/main/java/com/bloodstar/fluxragcompute/agent/RagAgent.java`

## 它的职责
> 先检索知识，再让大模型回答。

你可以把它理解为：
- 它自己不直接知道知识
- 它先让工具去 Milvus 查资料
- 再拿资料给大模型组织答案

## 核心方法：`answer(String question)`

```java
public String answer(String question) {
    ToolExecutionResult searchResult = milvusSearchTool.apply(new MilvusSearchRequest(question, 4));
    if (!searchResult.success()) {
        return "知识检索失败：" + searchResult.message();
    }
    List<?> snippets = searchResult.data() instanceof List<?> list ? list : List.of();
    if (snippets.isEmpty()) {
        return "知识库里还没有命中相关片段。你可以先通过文档导入接口把架构文档灌入向量库，再来提问。";
    }
    String prompt = """
            你是 DataPilot 的 RAG Agent。
            ...
            """.formatted(question, toJson(snippets));
    return chatClient.call(prompt);
}
```

## 逐句解释

### `FunctionFunction<MilvusSearchRequest, ToolExecutionResult> milvusSearchTool`
这个类型你第一次看可能有点绕。

它表示：
- 输入一个 `MilvusSearchRequest`
- 输出一个 `ToolExecutionResult`

也就是一个函数式工具。

你可以把它理解成：
> 这是一个“可调用的工具对象”。

### `@Qualifier("milvusSearchTool")`
因为 Spring 容器里可能有多个 `FunctionFunction<...>` 类型的 Bean。

所以这里要明确告诉 Spring：
- 我要注入名字叫 `milvusSearchTool` 的那个 Bean

### `milvusSearchTool.apply(...)`
`Function` 接口的调用方式就是 `apply()`。

相当于：
- 调用工具
- 输入参数是 `MilvusSearchRequest`
- 返回工具结果

### `new MilvusSearchRequest(question, 4)`
这里用到了 **record**。

`MilvusSearchRequest` 不是普通 class，而是 Java record。

你可以把 record 理解成：
> 一种专门用来装数据的轻量对象，自动带构造器、getter、equals、hashCode、toString。

所以它不用手写 getter/setter。

### `List<?> snippets = searchResult.data() instanceof List<?> list ? list : List.of();`
这是这份代码里一个相对陌生的语法。

它包含两层知识点。

#### 第一层：`instanceof` 模式匹配
传统写法是：

```java
if (searchResult.data() instanceof List<?>) {
    List<?> list = (List<?>) searchResult.data();
}
```

现在可以直接写成：

```java
searchResult.data() instanceof List<?> list
```

意思是：
- 如果它确实是 List
- 那就顺便把它命名成 `list`

#### 第二层：三元表达式
如果是 List，就用这个 list；否则用空列表 `List.of()`。

### `List.of()`
Java 9+ 的不可变集合创建方式。

比如：

```java
List.of()
Map.of("a", 1)
```

都很常见。

### `toJson(snippets)`
把检索结果转成 JSON 字符串，拼到 Prompt 里。

这样大模型看到的是结构化上下文，而不是凌乱文本。

---

## 5.7 `RagAgent` 里的 `toJson()`

```java
private String toJson(Object value) {
    try {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException ex) {
        return String.valueOf(value);
    }
}
```

## 它的职责
把对象优雅地转成 JSON 字符串。

### `writerWithDefaultPrettyPrinter()`
意思是：
- 生成“格式化更好看”的 JSON
- 不是一坨紧凑字符串

### `writeValueAsString(value)`
对象转 JSON。

### 为什么 catch 异常后返回 `String.valueOf(value)`？
这是一个兜底。

即使 JSON 序列化失败，也至少返回一个字符串，不让整个流程因为这个小步骤崩掉。

---

## 5.8 DBA Agent：`DbaAgent`

文件：`src/main/java/com/bloodstar/fluxragcompute/agent/DbaAgent.java`

这是全项目最值得你精读的类之一，因为它体现了：
- 大模型如何参与生成 SQL
- 生成出来的 SQL 如何被限制
- 最终结果如何再次交给模型总结

## 它的职责
> 帮用户做数据库分析，但只能走只读查询。

它的处理流程是：
1. 先读表结构
2. 再生成 SQL
3. 再执行 SQL
4. 再让大模型总结结果

## 核心方法：`answer(String question)`

### 第一步：读表结构

```java
ToolExecutionResult schemaResult = schemaReaderTool.apply(new SchemaReadRequest("slow_query_log"));
```

意思是：
- 调用 `schemaReaderTool`
- 读取 `slow_query_log` 表结构

为什么先读表结构？

因为直接让大模型瞎写 SQL 很容易写错字段名。

### 第二步：生成 SQL 计划

```java
SqlGenerationResult sqlPlan = generatePlan(question, schemaResult.data(), null, null);
```

这里不是直接执行，而是先生成一个 SQL 方案。

### 第三步：执行 SQL

```java
ToolExecutionResult executionResult = sqlExecutorTool.apply(new SqlExecutionRequest(sqlPlan.sql()));
```

调用 SQL 执行工具。

注意：
- 这里并不是直接 JDBC 执行字符串
- 中间还会进安全沙盒

### 第四步：失败后自动重试一次

```java
if (!executionResult.success()) {
    sqlPlan = generatePlan(question, schemaResult.data(), sqlPlan.sql(), executionResult.message());
    executionResult = sqlExecutorTool.apply(new SqlExecutionRequest(sqlPlan.sql()));
}
```

这是个很有意思的工程设计。

意思是：
- 如果第一次生成的 SQL 执行失败
- 就把“上一轮 SQL”和“错误信息”再喂给模型
- 让它重新修正一次

这有点像“自我纠错”。

### 第五步：把结果交给模型总结

```java
String summaryPrompt = """
    ...
""".formatted(question, sqlPlan.analysis(), sqlPlan.sql(), toJson(executionResult.data()));
return chatClient.call(summaryPrompt);
```

注意这里的思路：
- 模型负责“理解问题”和“总结结果”
- 真正查数据的是工具

也就是说：
> 模型不直接碰数据库，模型通过工具间接获取数据。

这个思路你一定要建立起来。

---

## 5.9 `DbaAgent.generatePlan()`

```java
private SqlGenerationResult generatePlan(String question, Object schema, String previousSql, String errorMessage)
```

## 它的职责
让大模型根据：
- 用户问题
- 表结构
- 上一轮 SQL
- 上一轮错误

来生成一个新的只读 SQL。

## 为什么它重要？
因为这里体现了 Prompt Engineering 的核心思想：

你不是让模型随便写，而是明确告诉它：
- 只能只读
- 只能 JSON 输出
- 尽量带 ORDER BY 和 LIMIT
- 错了就根据错误再改

## 这句很关键

```java
if (result == null || !StringUtils.hasText(result.sql())) {
    return fallbackPlan(question, errorMessage);
}
```

意思是：
- 如果模型没有稳定返回结构化 JSON
- 或者压根没给 SQL
- 不要崩
- 走程序员写死的兜底 SQL

这就是很典型的“AI 不可靠，业务必须可控”。

---

## 5.10 `DbaAgent.fallbackPlan()`

```java
private SqlGenerationResult fallbackPlan(String question, String errorMessage) {
    String analysis = "模型未稳定返回结构化 SQL，已回退到确定性慢查询分析模板";
    String lower = question == null ? "" : question.toLowerCase();
    if (lower.contains("用户") || lower.contains("user")) {
        return new SqlGenerationResult(analysis, "SELECT user, COUNT(*) AS query_count, AVG(execution_time_ms) AS avg_execution_time_ms, MAX(execution_time_ms) AS max_execution_time_ms FROM slow_query_log GROUP BY user ORDER BY max_execution_time_ms DESC LIMIT 10");
    }
    ...
}
```

## 它的职责
当模型不稳定时，直接用程序员预先定义的 SQL 模板。

这段代码的价值在于：
- 保证 Demo 可演示
- 保证项目不会完全依赖模型发挥

---

## 5.11 SQL 安全沙盒：`SecuritySandboxService`

文件：`src/main/java/com/bloodstar/fluxragcompute/service/SecuritySandboxService.java`

这是整个项目最关键的“安全边界”之一。

## 它的职责
> 拦截危险 SQL，只允许只读语句通过。

## 为什么不能直接用字符串判断？
比如你可能会想：
- 包含 `update` 就拦截不就好了？

但这种方式很容易被绕过，比如：
- 注释
- 大小写变化
- 多语句拼接
- 更复杂的语法结构

所以这里用了 **Druid AST 解析**。

AST 的意思你可以粗略理解成：
> 先把 SQL 解析成语法树，再判断它到底是什么类型的语句。

### 核心方法：`validateReadOnlySql(String sql)`

```java
public void validateReadOnlySql(String sql) {
    if (!StringUtils.hasText(sql)) {
        throw new UnsafeSqlException("SQL 不能为空");
    }
    List List<SQLStatement> statements = SQLUtils.parseStatements(sql, DbType.mysql);
    if (statements.size() != 1) {
        throw new UnsafeSqlException("仅允许执行单条只读 SQL");
    }
    SQLStatement statement = statements.get(0);
    if (!isReadOnlyStatement(statement)) {
        throw new UnsafeSqlException("检测到非只读 SQL，已被安全沙盒拦截");
    }
}
```

## 逐句解释

### `SQLUtils.parseStatements(sql, DbType.mysql)`
把 SQL 按 MySQL 方言解析成 AST 语句对象。

### `statements.size() != 1`
不允许多语句。

比如这种要拦截：

```sql
select * from slow_query_log; delete from user;
```

### `isReadOnlyStatement(statement)`
判断语句是否属于允许的只读类型。

### `throw new UnsafeSqlException(...)`
发现危险 SQL，直接抛业务异常。

这个异常后面会被全局异常处理器接住，并返回给前端。

---

## 5.12 `isReadOnlyStatement()`

```java
private boolean isReadOnlyStatement(SQLStatement statement) {
    if (statement instanceof SQLSelectStatement) {
        return true;
    }
    String simpleName = statement.getClass().getSimpleName();
    if (EXTRA_ALLOWED_READ_ONLY_TYPES.contains(simpleName)) {
        return true;
    }
    return simpleName.contains("Show") || simpleName.contains("Explain") || simpleName.contains("Desc");
}
```

## 它的职责
判断 AST 语句对象是否是只读 SQL。

### `instanceof SQLSelectStatement`
如果是 `SELECT`，直接通过。

### `statement.getClass().getSimpleName()`
拿到类名，比如：
- `MySqlShowStatement`
- `SQLExplainStatement`

### `EXTRA_ALLOWED_READ_ONLY_TYPES`
这是一个白名单集合，列出了额外允许的只读语句类型。

### 为什么最后还用 `contains("Show")` 这种判断？
这是为了做兼容兜底。

有些具体实现类名不一定完全一致，所以再用名称特征补一层。

---

## 5.13 Milvus 检索工具：`MilvusSearchTool`

文件：`src/main/java/com/bloodstar/fluxragcompute/tools/MilvusSearchTool.java`

## 它的职责
从向量库中检索和用户问题最相似的文档片段。

## 为什么它被设计成 `FunctionFunction<Req, Resp>`？
因为 Spring AI 的工具调用风格就是这样的：
- 输入一个请求对象
- 输出一个结果对象

### 核心方法

```java
@Bean("milvusSearchTool")
public Function Function<MilvusSearchRequest, ToolExecutionResult> milvusSearchTool(VectorStore vectorStore)
```

## 逐句解释

### `@Configuration`
表示这个类是配置类，里面可以声明 Bean。

### `@Bean("milvusSearchTool")`
把这个方法返回的对象注册成 Spring Bean，名字叫 `milvusSearchTool`。

### `FunctionFunction<MilvusSearchRequest, ToolExecutionResult>`
工具输入输出类型。

### `return request -> { ... }`
这是 **Lambda 表达式**。

等价于写一个匿名实现类。

你可以先粗糙地理解成：
> 返回一个“拿到 request 后就执行里面逻辑”的函数对象。

### `int topK = request.topK() == null ? 4 : Math.max(1, Math.min(request.topK(), 8));`
这是一个很典型的参数收敛逻辑。

意思是：
- 没传 topK，就默认 4
- 最少 1
- 最多 8

### `vectorStore.similaritySearch(...)`
去向量库做相似度检索。

### `SearchRequest.builder()`
又是 Builder 模式。

### `documents.stream().map(...).collect(...)`
这是 Java Stream API。

作用是：
- 遍历检索到的 `documents`
- 每条文档转换成 `Map`
- 最后收集成 `List`

如果你对 Stream 不熟，可以理解成“函数式 for 循环”。

---

## 5.14 表结构读取工具：`SchemaReaderTool`

文件：`src/main/java/com/bloodstar/fluxragcompute/tools/SchemaReaderTool.java`

## 它的职责
读取 `information_schema.columns`，把某张表的结构查出来。

## 为什么这个工具重要？
因为大模型写 SQL 前，先知道：
- 表名
- 字段名
- 字段类型

会大大减少生成错误字段的概率。

### 核心逻辑

```java
List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
    SELECT column_name,
           column_type,
           is_nullable,
           column_key,
           column_comment
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = ?
    ORDER BY ordinal_position
    """, tableName);
```

## 你需要学到什么？

### `JdbcTemplate`
这是 Spring JDBC 提供的数据库操作模板。

你可以把它理解成：
- 比原生 JDBC 省事很多
- 不用手写获取连接、释放资源这些样板代码

### `queryForList(...)`
执行查询并返回 `List<Map<String, Object>>`。

也就是：
- 每一行是一张 Map
- key 是列名
- value 是列值

### SQL 里的 `?`
这是占位符，后面的 `tableName` 会作为参数传进去，防止拼接 SQL。

---

## 5.15 SQL 执行工具：`SqlExecutorTool`

文件：`src/main/java/com/bloodstar/fluxragcompute/tools/SqlExecutorTool.java`

## 它的职责
执行 SQL，但执行之前必须先过安全沙盒。

这是工具层里最关键的类。

## 核心逻辑

```java
securitySandboxService.validateReadOnlySql(rawSql);
String executableSql = applyDefaultLimit(rawSql, maxLimit);
List<Map<String, Object>> rows = jdbcTemplate.queryForList(executableSql);
```

处理顺序非常重要：
1. 先校验安全
2. 再补 limit
3. 再执行查询

### `@Value("${datapilot.query.max-limit:100}")`
这是配置注入。

意思是：
- 去配置文件里读取 `datapilot.query.max-limit`
- 如果没有，就默认 `100`

### `LinkedHashMap<>`
这里选 `LinkedHashMap` 的原因是：
- 它保留插入顺序
- 输出 JSON 时字段顺序更稳定

### 两个 catch

```java
} catch (UnsafeSqlException ex) {
    return ToolExecutionResult.failure(ex.getMessage());
} catch (Exception ex) {
    return ToolExecutionResult.failure("SQL 执行失败: " + ex.getMessage());
}
```

意思是：
- 如果是安全异常，直接返回明确安全错误
- 如果是别的执行异常，也包装成失败结果

注意这里没有把异常继续往上抛，而是**转成工具结果返回**。

这是工具层常见写法，因为工具调用更偏向“返回结构化结果”。

---

## 5.16 `applyDefaultLimit()`

```java
private String applyDefaultLimit(String sql, int maxLimit) {
    String normalized = sql.toLowerCase();
    if (!normalized.startsWith("select") || normalized.contains(" limit ")) {
        return sql;
    }
    return sql + " limit " + maxLimit;
}
```

## 它的职责
如果 SQL 是 `select`，但没写 `limit`，就自动补一个默认上限。

## 为什么要这么做？
因为大模型可能生成：

```sql
select * from slow_query_log
```

如果数据量很大，这就不适合直接查。

所以这里是一个很典型的“安全保护 + 性能保护”。

---

## 5.17 文档导入核心：`DocumentIngestService`

文件：`src/main/java/com/bloodstar/fluxragcompute/service/DocumentIngestService.java`

这是整个文档链路的核心类。

## 它的职责
完成以下完整流程：
1. 根据消息里的文件路径定位文件
2. 创建 `knowledge_document` 记录
3. 读取文件内容
4. 把文本切片
5. 把切片写到 Milvus
6. 把切片元数据写到 MySQL
7. 更新导入状态

## 核心方法：`ingest(DocumentIngestMessage message)`

### 第一步：拿到路径

```java
Path path = Path.of(message.getFilePath());
```

这里用的是 Java NIO 的 `Path`。

优点是跨平台：
- macOS
- Linux
- Windows

都能统一处理。

### 第二步：先创建文档记录

```java
KnowledgeDocument document = createDocument(path);
```

这一步很重要。

意思是：
- 即使后面失败了
- 数据库里也会留下这次任务记录
- 只是状态会是 `FAILED`

这比“失败了什么都不留”更利于排查。

### 第三步：检查文件是否存在

```java
if (!Files.exists(path)) {
    markFailed(document.getId(), "文件不存在");
    throw new IllegalStateException("文件不存在: " + path);
}
```

### 第四步：读取文件

```java
String content = Files.readString(path, StandardCharsets.UTF_8);
```

### 第五步：检查空文件

```java
if (!StringUtils.hasText(content)) {
    markFailed(document.getId(), "文件为空");
    throw new IllegalStateException("文件为空: " + path);
}
```

### 第六步：切片

```java
List<String> segments = splitText(content, 500, 100);
```

意思是：
- 每片 500 个字符
- 相邻片段重叠 100 个字符

为什么要重叠？

因为纯粹硬切分会把上下文切断，重叠能保留一部分衔接信息。

### 第七步：组装向量文档和数据库实体

```java
for (int i = 0; i < segments.size(); i++) {
    ...
    vectorDocuments.add(new Document(...));
    KnowledgeSegment segment = new KnowledgeSegment();
    ...
    segmentEntities.add(segment);
}
```

这里在做两件事：
- 准备写入 Milvus 的 `Document`
- 准备写入 MySQL 的 `KnowledgeSegment`

这也是为什么你会看到“同一份内容写两处”。

因为：
- Milvus 负责向量检索
- MySQL 负责业务数据留痕

### 第八步：写入向量库

```java
vectorStore.add(vectorDocuments);
```

### 第九步：写入数据库

```java
for (KnowledgeSegment segmentEntity : segmentEntities) {
    knowledgeSegmentMapper.insert(segmentEntity);
}
```

### 第十步：更新状态

```java
updateStatus(document.getId(), STATUS_COMPLETED);
```

---

## 5.18 `splitText()`

```java
public List<String> splitText(String content, int windowSize, int overlap) {
    ...
    int step = windowSize - overlap;
    for (int start = 0; start < content.length(); start += step) {
        int end = Math.min(content.length(), start + windowSize);
        String segment = content.substring(start, end).trim();
        ...
    }
    return segments.isEmpty() ? List.of(content.trim()) : segments;
}
```

## 它的职责
按滑动窗口切分文本。

## 举个例子
如果：
- `windowSize = 500`
- `overlap = 100`

那么：
- 第一段：0 ~ 500
- 第二段：400 ~ 900
- 第三段：800 ~ 1300

这样每一段都会和前一段保留 100 字重叠。

### `Math.min(...)`
防止最后一段越界。

### `segments.isEmpty() ? List.of(content.trim()) : segments`
意思是：
- 如果切完一段都没有，就兜底返回原文一段
- 否则返回切好的列表

---

## 5.19 `createDocument()` / `updateStatus()` / `markFailed()`

这三个方法都属于“辅助方法”，但非常体现工程化。

### `createDocument()`
创建一条 `knowledge_document` 记录，初始状态设为 `PROCESSING`。

### `updateStatus()`
根据 id 更新状态。

### `markFailed()`
做两件事：
1. 打错误日志
2. 把状态改成 `FAILED`

这三个方法让主流程 `ingest()` 更清晰，不会全部逻辑都堆在一个方法里。

---

## 5.20 RabbitMQ 配置：`RabbitMqConfig`

文件：`src/main/java/com/bloodstar/fluxragcompute/config/RabbitMqConfig.java`

这个类不是业务核心，但你必须知道它的作用。

## 它的职责
声明 MQ 基础设施：
- 队列
- 交换机
- 绑定关系
- 消息转换器

### `documentQueue()`
返回一个队列对象。

### `documentExchange()`
返回一个直连交换机 `DirectExchange`。

### `documentBinding(...)`
把队列绑定到交换机，并指定 routing key。

### `jacksonMessageConverter()`
指定消息体序列化/反序列化用 Jackson。

这个很重要，否则 Java 对象消息在收发时容易出问题。

---

## 5.21 MQ 监听器：`DocumentMqListener`

文件：`src/main/java/com/bloodstar/fluxragcompute/mq/DocumentMqListener.java`

## 它的职责
监听队列，拿到消息后调用文档导入服务。

```java
@RabbitListener(queues = MqConstants.DOCUMENT_QUEUE)
public void onMessage(DocumentIngestMessage message) {
    log.info("Receive document ingest message: {}", message.getFilePath());
    documentIngestService.ingest(message);
}
```

### `@RabbitListener`
告诉 Spring：
- 这个方法要监听指定队列
- 一旦有消息到达，就自动调用这个方法

### `log.info("... {}", message.getFilePath())`
这是 Slf4j 的占位符日志写法。

比字符串拼接更规范，性能也更好。

---

## 5.22 文档导入接口：`DocumentController`

文件：`src/main/java/com/bloodstar/fluxragcompute/controller/DocumentController.java`

## 它的职责
接收导入请求，但不自己处理文件，而是把任务投递到 RabbitMQ。

### 核心代码

```java
rabbitTemplate.convertAndSend(MqConstants.DOCUMENT_EXCHANGE, MqConstants.DOCUMENT_ROUTING_KEY, message);
```

意思是：
- 往指定交换机发消息
- 带上路由键
- 消息内容就是 `DocumentIngestMessage`

### 为什么不直接在 Controller 里读文件？
因为文档导入可能比较慢：
- 要读文件
- 要切片
- 要写 Milvus
- 要写 MySQL

如果都放到 HTTP 请求线程里，响应会很慢。

所以这里用了异步化。

---

## 5.23 统一异常处理：`GlobalExceptionHandler`

文件：`src/main/java/com/bloodstar/fluxragcompute/exception/GlobalExceptionHandler.java`

## 它的职责
统一接住各种异常，并返回规范 JSON 响应。

这和你传统 Spring Boot 项目里的全局异常处理是一致的。

### `@RestControllerAdvice`
表示：
- 这是一个全局增强类
- 专门给所有 Controller 做异常处理
- 返回值也会直接转 JSON

### 典型处理方法

#### `handleMethodArgumentNotValid(...)`
处理 `@Valid` 参数校验失败。

#### `handleUnsafeSql(...)`
处理危险 SQL 异常，返回 403。

#### `handleIllegalState(...)`
处理业务状态异常，比如文件不存在。

#### `handleException(...)`
最后的兜底异常处理。

---

# 6. DTO、Entity、Mapper 怎么看

这些部分你会比较熟悉，我用更快一点的方式讲。

---

## 6.1 DTO 层

### `ChatRequest`
```java
@Data
public class ChatRequest {
    @NotBlank(message = "message 不能为空")
    private String message;
}
```

职责：聊天接口的请求体。

重点：
- `@NotBlank`：不能为空白字符串
- `@Data`：Lombok 自动生成 getter/setter/toString/equals/hashCode

### `ChatResponse`
```java
@Data
@Builder
public class ChatResponse {
    private String target;
    private String reason;
    private String answer;
}
```

职责：聊天业务返回的数据体。

### `ApiResponse<T>`
统一响应包装。

这里的 `<T>` 是 Java 泛型。

意思是：
- 它可以包装任意类型的数据
- `T` 是占位符

比如：
- `ApiResponseResponse<ChatResponse>`
- `ApiResponse<Map<String, String>>`

### `RouteDecision`
路由结果对象。

重点方法：
- `normalizedTarget()`：把目标统一规范化

### `DocumentIngestMessage`
文档导入消息体。

它既可以作为：
- HTTP 请求参数
- MQ 消息对象

### `ToolPayloads`
这个类用了 Java `record` 来装很多工具相关对象。

比如：
- `MilvusSearchRequest`
- `SchemaReadRequest`
- `SqlExecutionRequest`
- `ToolExecutionResult`
- `SqlGenerationResult`

这种写法在“只负责装数据”的场景非常合适。

---

## 6.2 Entity 层

### `KnowledgeDocument`
对应表：`knowledge_document`

字段含义：
- `id`：主键
- `fileName`：文件名
- `fileUrl`：文件路径
- `status`：导入状态
- `createTime`：创建时间

### `KnowledgeSegment`
对应表：`knowledge_segment`

字段含义：
- `documentId`：属于哪个文档
- `content`：片段内容
- `vectorId`：向量 id

### `SlowQueryLog`
对应表：`slow_query_log`

字段含义：
- `sqlText`：SQL 文本
- `executionTimeMs`：执行耗时
- `user`：用户
- `happenTime`：发生时间

### 你可能陌生的注解

#### `@TableName("...")`
指定实体映射到哪张表。

#### `@TableId(type = IdType.ASSIGN_ID)`
主键策略是 MyBatis-Plus 的 `ASSIGN_ID`。

可以简单理解成：
> 框架会自动帮你生成一个分布式风格的 Long 主键。

---

## 6.3 Mapper 层

例如：

```java
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapperMapper<KnowledgeDocument> {
}
```

## 怎么理解？
这和你以前自己写 Mapper 接口很像，但这里更省事。

因为它继承了 `BaseMapper<T>`，你直接就有：
- `insert`
- `deleteById`
- `updateById`
- `selectById`
- 等常用 CRUD

所以当前项目里不需要额外写 XML。

---

# 7. 配置文件怎么读

虽然你说不需要把细枝末节配置讲太多，但它们至少要知道是干什么的。

---

## 7.1 `application.yml`

文件：`src/main/resources/application.yml`

## 重点配置块

### `server.port: 8081`
服务端口。

### `spring.threads.virtual.enabled: true`
开启虚拟线程。

你先不用深究，简单理解成：
> Java 21 提供的一种更轻量线程能力，适合高并发 I/O 场景。

### `spring.datasource`
MySQL 和 Druid 配置。

### `spring.sql.init`
启动时自动执行 `schema.sql`。

### `spring.rabbitmq`
RabbitMQ 连接配置。

### `spring.data.redis`
Redis 连接配置。

目前代码里还没有真正使用 Redis，但配置已经预留了。

### `spring.ai.openai`
大模型接口配置。

这里虽然叫 `openai`，但实际上 `base-url` 指向的是 ModelScope 兼容接口。

### `spring.ai.vectorstore.milvus`
Milvus 向量库配置。

### `mybatis-plus`
MyBatis-Plus 配置。

其中：
- `map-underscore-to-camel-case: true`
  表示数据库下划线字段自动映射到驼峰 Java 属性

例如：
- `file_name -> fileName`
- `execution_time_ms -> executionTimeMs`

### `datapilot.query.max-limit: 100`
SQL 默认查询上限。

---

## 7.2 `schema.sql`

文件：`src/main/resources/sql/schema.sql`

这里定义了三张表：
- `knowledge_document`
- `knowledge_segment`
- `slow_query_log`

### 你要重点记住这三张表的作用

#### `knowledge_document`
记录“导入了哪些文档”。

#### `knowledge_segment`
记录“文档被切成了哪些片段”。

#### `slow_query_log`
模拟 DBA 分析场景下要查询的业务表。

---

# 8. 你可能陌生的语法糖速查

这一节非常重要，我专门给你做个“读代码时随手查”的清单。

---

## 8.1 Lombok 注解

### `@Data`
自动生成：
- getter
- setter
- toString
- equals
- hashCode

### `@Builder`
自动生成构建器。

### `@RequiredArgsConstructor`
自动生成“所有 final 字段”的构造器。

### `@Slf4j`
自动生成日志对象：

```java
private static final Logger log = LoggerFactory.getLogger(当前类.class);
```

---

## 8.2 Spring 常见注解

### `@Component`
普通组件 Bean。

### `@Service`
业务组件 Bean。

### `@RestController`
控制器，并直接返回 JSON。

### `@Configuration`
配置类。

### `@Bean`
把一个方法返回值注册为 Bean。

### `@Qualifier("beanName")`
指定注入哪一个 Bean。

### `@Value("${...}")`
从配置文件里读配置。

### `@RabbitListener`
监听 MQ 队列。

### `@Transactional`
事务控制。

`rollbackFor = Exception.class` 表示：
- 只要抛出 `Exception` 及其子类，就回滚

---

## 8.3 Java 新写法

### 文本块
```java
"""
多行字符串
"""
```

### record
```java
public record SqlExecutionRequest(String sql) {}
```

就是轻量数据类。

### Lambda
```java
request -> { ... }
```

就是函数式写法。

### Stream
```java
documents.stream().map(...).collect(...)
```

就是集合函数式处理。

### 三元运算符
```java
条件 ? 值1 : 值2
```

### `Map.of(...)` / `List.of(...)`
快速创建不可变集合。

### `instanceof` 模式匹配
```java
obj instanceof List<?> list
```

如果 obj 是 List，就直接把它命名为 `list`。

---

# 9. 如果你要真正开始读源码，建议顺序

我建议你按这个顺序读，不要一上来就看全部。

## 第一轮：先读懂主流程
1. `DataPilotApplication`
2. `CopilotController`
3. `CopilotService`
4. `RouterAgent`
5. `RagAgent`
6. `DbaAgent`

这一轮目标只有一个：
> 明白聊天请求是怎么一步步走下去的。

## 第二轮：读懂工具层
7. `MilvusSearchTool`
8. `SchemaReaderTool`
9. `SqlExecutorTool`
10. `SecuritySandboxService`

这一轮目标：
> 明白 Agent 并不是直接操作数据库和向量库，而是通过工具间接操作。

## 第三轮：读懂文档导入链路
11. `DocumentController`
12. `RabbitMqConfig`
13. `DocumentMqListener`
14. `DocumentIngestService`

这一轮目标：
> 明白一个文件是怎么被异步导入知识库的。

## 第四轮：补基础支撑代码
15. DTO
16. Entity
17. Mapper
18. `GlobalExceptionHandler`
19. `application.yml`
20. `schema.sql`

---

# 10. 你现在最容易卡住的点，我提前帮你指出来

## 10.1 “Agent 看起来很玄乎”
其实先别想复杂。

你暂时把它理解成：
- 一个封装了 Prompt + 大模型调用 + 工具协作的 Service 类

它不是魔法，本质上还是 Java 类。

## 10.2 “Tool 为什么不是普通 Service 方法？”
因为它是为 AI 工具调用风格设计的。

但你完全可以把它类比成：
- 对外暴露了一组更标准化的业务能力
- 只不过输入输出被统一封装成了 `Function<Request, Response>`

## 10.3 “为什么不是传统 service 调 mapper 就完了？”
因为这里多了一个“大模型参与决策”的环节。

比如 DBA 问答不是写死 SQL，而是：
- 先让模型理解自然语言
- 生成 SQL
- 再受控执行

所以才拆出了 Agent 和 Tool。

## 10.4 “为什么还有安全沙盒？”
因为只要涉及“模型生成 SQL”，安全边界就是必须的。

否则模型一旦生成：
- `delete`
- `update`
- `drop`

你的数据库就危险了。

---

# 11. 用一句话总结每个核心类

如果你想快速复习，就看这一节。

- `DataPilotApplication`：启动整个 Spring Boot 项目
- `CopilotController`：聊天接口入口
- `DocumentController`：文档导入接口入口
- `CopilotService`：聊天总调度
- `RouterAgent`：判断问题走 RAG 还是 DBA
- `RagAgent`：先检索知识，再回答
- `DbaAgent`：先读表结构，再生成并执行安全 SQL，最后总结
- `MilvusSearchTool`：查向量库
- `SchemaReaderTool`：查数据库表结构
- `SqlExecutorTool`：安全执行只读 SQL
- `SecuritySandboxService`：拦截危险 SQL
- `DocumentIngestService`：文档读入、切片、入库
- `RabbitMqConfig`：声明 MQ 基础设施
- `DocumentMqListener`：消费文档导入消息
- `GlobalExceptionHandler`：统一处理异常
- `KnowledgeDocument`：文档记录实体
- `KnowledgeSegment`：文档片段实体
- `SlowQueryLog`：慢查询模拟表实体

---

# 12. 给你的学习建议

你现在最不需要做的事，是试图一次性把 Spring AI、Milvus、RabbitMQ、MyBatis-Plus 全部吃透。

你应该按下面的节奏来：

### 第一步
先把它当成一个“增强版 Spring Boot 项目”看。
只抓主线：
- 请求怎么进
- 业务怎么分流
- 数据怎么落库

### 第二步
再去理解 Agent / Tool 这两个新概念。

### 第三步
最后再补：
- 向量库是什么
- RAG 是什么
- AST SQL 校验是什么

这样你会轻松很多。

---

# 13. 你接下来可以怎么用这份文档

我建议你这样配合源码看：

1. 左边打开这个 `LEARNING_GUIDE.md`
2. 右边打开对应 Java 文件
3. 按照本文第 9 节的顺序一个个看
4. 每看完一个类，就自己试着回答：
   - 这个类的职责是什么？
   - 它依赖谁？
   - 它对外提供哪个方法？
   - 它在主流程的哪个位置？

如果你愿意，下一步我可以继续帮你做两件非常适合你当前阶段的事：

1. **再写一份“时序图版学习文档”**，专门画出每个请求怎么流转
2. **按类带你精读源码**，比如下一条就只讲 `DbaAgent`，我把每一行都掰开讲

如果你想，我建议下一步先讲：
> `CopilotService + RouterAgent + DbaAgent`

因为这一组最能帮你建立“这个项目到底怎么跑起来”的感觉。