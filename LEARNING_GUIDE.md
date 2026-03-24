# DataPilot 学习导读（升级版）

这份文档是给你看源码用的学习文件。它会重点解释：
- 目录是做什么的
- 核心类的职责是什么
- 每个核心方法是干什么的
- 你现在最容易陌生的语法糖和新概念

这次升级之后，项目已经和最初版本不一样了，最重要的变化有 4 个：

1. 文档入口从“传本地路径”改成了“真正上传文件”
2. 文件不再依赖应用本机磁盘长期存储，而是上传到 OSS
3. 文档解析改成了 Apache Tika
4. 响应和异常改成了 `code + message + data` 风格

所以你现在再看这个项目时，要把它当成一个：

> 传统 Spring Boot 项目 + AI 调度 + 对象存储 + 异步文档处理

而不是只把它当成一个单纯的三层 CRUD 项目。

---

## 1. 现在这个项目到底在做什么？

一句话概括：

> 用户上传文档后，系统把文件上传到 OSS，再异步解析并写入 Milvus；用户聊天时，系统先判断这是知识类问题还是数据库类问题，然后走 RAG 或 DBA 链路。

当前有三条核心业务链路：

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

### 1.3 聊天问答链路
```text
用户问题
-> CopilotController
-> CopilotService
-> RouterAgent 路由
-> RagAgent / DbaAgent
-> Tool
-> Milvus / MySQL
-> 返回统一响应
```

---

## 2. 目录说明（升级后）

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
放 AI 角色类：
- `RouterAgent`：分流
- `RagAgent`：知识问答
- `DbaAgent`：数据库分析

### `common`
放统一响应和错误码：
- `ErrorCode`
- `BaseResponse`
- `ResultUtils`

这是这次升级新增的重点目录。

### `config`
放配置类：
- `RabbitMqConfig`
- `MybatisPlusConfig`
- `OssProperties`
- `DocumentSplitProperties`

### `controller`
对外 HTTP 入口：
- `CopilotController`
- `DocumentController`

### `dto`
放请求响应对象和中间数据对象，比如：
- `ChatRequest`
- `ChatResponse`
- `DocumentIngestMessage`
- `DocumentUploadResponse`
- `ParsedDocument`
- `StorageObjectInfo`

### `entity`
数据库实体：
- `KnowledgeDocument`
- `KnowledgeSegment`
- `SlowQueryLog`

### `exception`
异常体系：
- `BusinessException`
- `GlobalExceptionHandler`

### `mapper`
MyBatis-Plus Mapper 接口。

### `mq`
RabbitMQ 消费者：
- `DocumentMqListener`

### `service`
现在这个目录最重要，里面既有传统业务服务，也有新能力服务：
- `CopilotService`
- `SecuritySandboxService`
- `DocumentUploadService`
- `DocumentIngestService`
- `ObjectStorageService`
- `DocumentParsingService`
- `DocumentSplittingService`
- `service.impl` 下放具体实现

### `tools`
给 Agent 用的受控工具：
- `MilvusSearchTool`
- `SchemaReaderTool`
- `SqlExecutorTool`

### `utils`
放小工具类：
- `ThrowUtils`

你之前说“抛异常用的工具类叫什么来着”，这个项目里我用了最常见的名字：`ThrowUtils`。

---

## 3. 这次升级后，你最应该优先读哪些类？

建议顺序：

1. `DocumentController`
2. `DocumentUploadService`
3. `ObjectStorageService` / `AliyunOssStorageService`
4. `DocumentMqListener`
5. `DocumentIngestService`
6. `TikaDocumentParsingService`
7. `DefaultDocumentSplittingService`
8. `CopilotController`
9. `CopilotService`
10. `RouterAgent`
11. `RagAgent`
12. `DbaAgent`
13. `SecuritySandboxService`
14. `GlobalExceptionHandler`
15. `ErrorCode / BaseResponse / ResultUtils / ThrowUtils`

因为这次升级最主要的新增价值，就是文档上传和异常响应这两块。

---

# 4. 核心类详解

---

## 4.1 `DocumentController`

职责：
- 暴露文档上传接口
- 不自己做上传和入库，只负责把文件交给 `DocumentUploadService`

现在它和最初版本最大的区别是：
- 以前接收的是 JSON 里的 `filePath`
- 现在接收的是 `MultipartFile`

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

这个类很薄，符合“Controller 只做入口分发”的设计。

---

## 4.2 `DocumentUploadService`

职责：
1. 校验上传文件
2. 把文件先存为临时文件
3. 上传到 OSS
4. 创建 `knowledge_document` 记录
5. 发送 MQ 消息，让异步消费者继续处理
6. 删除临时文件

你可以把它理解成“上传链路总协调器”。

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
- 本地临时文件只是“中转”，不是长期存储
- 真正长期存储是 OSS
- 解析和切分这些耗时操作不在上传接口同步完成，而是异步执行

这正是你之前说的那个方向：
> 本地文件只能是临时文件，不能成为长期依赖

---

## 4.3 `ObjectStorageService`

职责：
- 定义对象存储抽象接口

方法：
- `upload(...)`
- `download(...)`
- `getObjectUrl(...)`

这一步非常重要，因为它把“业务”和“具体云厂商 SDK”隔开了。

你可以这样理解：
- 业务代码只认接口
- 具体用阿里云还是腾讯云，是实现类的事情

这样以后如果你要扩展腾讯云 COS，就不需要重写整个上传链路。

---

## 4.4 `AliyunOssStorageService`

职责：
- `ObjectStorageService` 的阿里云 OSS 实现

它负责：
- 用阿里云 SDK 建客户端
- 生成对象 key
- 上传文件
- 下载文件
- 拼接文件 URL

### 关键知识点

#### `buildObjectKey()`
会把文件生成一个比较规整的对象路径，比如：
```text
datapilot/docs/2026/03/24/uuid-architecture.pdf
```

这样做比直接把原文件名丢到根目录更规范。

#### `validateProperties()`
在真正访问 OSS 之前，先检查：
- endpoint
- bucketName
- accessKeyId
- accessKeySecret

没有这些配置，就直接抛业务异常。

这比到了 SDK 层再报一长串底层错误更友好。

---

## 4.5 `DocumentMqListener`

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

这里你要记住：
- 上传接口只负责“发起任务”
- 真正解析文档的是 MQ 消费者这条异步链路

---

## 4.6 `DocumentIngestService`

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

### 你可以把它理解成：
> “文档异步入库总流程”的真正核心。

### 和最早版本的差异
最早版本是：
- `Path.of(filePath)`
- `Files.readString(...)`

现在是：
- `objectStorageService.download(objectKey)`
- `documentParsingService.parse(...)`
- `documentSplittingService.split(...)`

也就是说，职责更清晰了：
- OSS 负责存储
- Tika 负责解析
- splitter 负责切分
- 当前服务负责总编排

这就是比较像真实工程的写法。

---

## 4.7 `TikaDocumentParsingService`

职责：
- 用 Apache Tika 统一解析不同文档格式

支持方向：
- txt
- md
- pdf
- doc
- docx

### 关键逻辑
- 创建 `Tika`
- 传入 `InputStream`
- 返回解析后的纯文本和元数据

### 为什么用 Tika？
因为它很适合做“统一入口解析”：
- 不用你自己判断到底是 pdf 还是 docx
- 它会尽量自动选择合适解析器

这正是你提出的需求。

---

## 4.8 `DefaultDocumentSplittingService`

职责：
- 把长文档切成适合向量检索的小段

实现思路：
1. 优先使用 `TokenTextSplitter`
2. 如果失败，回退到滑动窗口切分

### 为什么这样设计？
因为你前面提到得很对：
- 纯滑动窗口虽然能解决部分问题
- 但还是不够灵活

所以这里采用“双轨方案”：
- 主方案：成熟 splitter
- 兜底方案：你原来熟悉的滑动窗口

这种设计很适合写在简历上，因为它体现的不是“我会堆框架”，而是：
> 我知道怎么在工程里既追求效果，又保留兜底可控性。

---

## 4.9 `ErrorCode`

职责：
- 定义统一错误码和默认错误消息

你可以把它理解成：
- 一个“错误字典”

比如：
- `SUCCESS(0, "ok")`
- `PARAMS_ERROR(40000, "请求参数错误")`
- `SYSTEM_ERROR(50000, "系统内部异常")`

前后端都能围绕这个错误码体系协作。

---

## 4.10 `BusinessException`

职责：
- 表示“可预期的业务错误”
- 里面带有 `code` 和 `message`

比如：
- 上传文件为空
- 文档记录不存在
- OSS 配置没填
- SQL 不安全

这些都更适合抛业务异常，而不是直接抛 RuntimeException 原始信息。

---

## 4.11 `ThrowUtils`

职责：
- 快速根据条件抛业务异常

你说你忘了这个工具类一般叫什么，这里我用了最常见的名字：
- `ThrowUtils`

典型写法：
```java
ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");
```

这比每次都手写：
```java
if (...) {
    throw new BusinessException(...);
}
```

更统一，也更短。

---

## 4.12 `BaseResponse` 和 `ResultUtils`

### `BaseResponse`
职责：
- 统一接口返回结构

字段：
- `code`
- `data`
- `message`

### `ResultUtils`
职责：
- 统一构造成功/失败响应

例如：
- `ResultUtils.success(data)`
- `ResultUtils.error(errorCode)`
- `ResultUtils.error(code, message)`

这和你说的“鱼皮风格”是同一类思路。

---

## 4.13 `GlobalExceptionHandler`

职责：
- 全局捕获异常并转成统一响应

处理三类主情况：
1. `BusinessException`
2. 参数校验异常
3. 普通 `Exception`

### 这里有个很重要的设计点
对于系统异常：
- 不直接把底层错误明文抛给前端
- 而是统一返回系统错误

这比早期版本的“直接把 `ex.getMessage()` 给前端”更稳。

---

## 4.14 `SecuritySandboxService`

职责没有变：
- 仍然是 SQL 安全沙盒

但异常风格变了：
- 不再抛老的 `UnsafeSqlException`
- 现在统一抛 `BusinessException`

这样整个项目异常模型就统一了。

---

## 4.15 聊天链路里哪些类没变主职责？

这些类虽然内部细节可能调整了，但主职责没变：
- `CopilotController`
- `CopilotService`
- `RouterAgent`
- `RagAgent`
- `DbaAgent`
- `MilvusSearchTool`
- `SchemaReaderTool`
- `SqlExecutorTool`

你现在读聊天链路时，可以沿用之前那份理解。

只是要注意：
- Controller 返回已经改成 `BaseResponse`
- 整个项目异常体系已经统一成 `ErrorCode + BusinessException + GlobalExceptionHandler`

---

# 5. 这次升级后，数据库实体怎么看

## `KnowledgeDocument`
以前只存：
- fileName
- fileUrl
- status

现在新增了：
- `storageProvider`
- `objectKey`
- `contentType`
- `failureReason`

这意味着：
- 你现在能知道文件是哪个存储提供方存的
- 能知道对象存储里的 key 是什么
- 能知道失败原因

这比 MVP 更接近真实工程。

## `KnowledgeSegment`
新增了：
- `segmentIndex`

用来标识文档片段的顺序。

---

# 6. 你现在最容易陌生的新点

## 6.1 `MultipartFile`
这是 Spring MVC 对上传文件的封装。

你可以理解成：
> Controller 里接收到的上传文件对象。

它能提供：
- 文件名
- 内容类型
- 输入流
- 是否为空
- transferTo() 保存到本地

## 6.2 OSS 抽象层
这不是传统三层里的标准层次，但非常常见。

本质上是：
- 给外部存储系统封装一个 Java 接口
- 业务代码只调接口，不直接耦合云厂商 SDK

## 6.3 Tika
可以先把它理解成：
- 一个“万能文档解析器入口”

当然不是所有文档都百分百完美，但它比手写 `Files.readString()` 强太多了。

## 6.4 `BaseResponse` 替代布尔 success
以前：
```json
{
  "success": true,
  "message": "OK",
  "data": {...}
}
```

现在：
```json
{
  "code": 0,
  "message": "ok",
  "data": {...}
}
```

为什么后者更常见？
因为：
- 更适合前端判断错误类型
- 更适合业务扩展
- 更适合日志和监控归类

---

# 7. 现在最推荐你的读代码顺序

如果你今天只想读最有价值的部分，就按这个顺序：

1. `DocumentController`
2. `DocumentUploadService`
3. `AliyunOssStorageService`
4. `DocumentMqListener`
5. `DocumentIngestService`
6. `TikaDocumentParsingService`
7. `DefaultDocumentSplittingService`
8. `ErrorCode`
9. `BusinessException`
10. `ThrowUtils`
11. `GlobalExceptionHandler`

这一轮的目标是：
> 先彻底读懂“上传一个文档之后，系统怎么把它变成知识库数据”的完整链路。

第二轮再去看聊天链路：

12. `CopilotController`
13. `CopilotService`
14. `RouterAgent`
15. `RagAgent`
16. `DbaAgent`
17. `SqlExecutorTool`
18. `SecuritySandboxService`

---

# 8. 一句话总结现在每个新增核心类

- `DocumentUploadService`：上传文件、转临时文件、传 OSS、落文档记录、发 MQ
- `ObjectStorageService`：对象存储抽象接口
- `AliyunOssStorageService`：阿里云 OSS 实现
- `TikaDocumentParsingService`：统一解析 pdf/doc/docx/md/txt
- `DefaultDocumentSplittingService`：优先 TokenTextSplitter，失败后回退滑动窗口
- `ErrorCode`：统一错误码定义
- `BaseResponse`：统一返回体
- `ResultUtils`：统一构造响应
- `BusinessException`：统一业务异常
- `ThrowUtils`：按条件快速抛业务异常

---

# 9. 给你现在的学习建议

你现在最应该重点建立的，不是“会不会用某个 SDK”，而是下面这几个工程意识：

1. **文件不应该长期依赖应用本地磁盘**
2. **解析、切分、向量化这些重操作要异步化**
3. **对象存储和业务代码要解耦**
4. **异常和响应要统一风格**
5. **AI 能力必须被工具和安全边界约束**

这几条一旦建立起来，你再看这个项目，会从“好多新东西我看不懂”变成：
> 它其实还是一个 Spring Boot 项目，只是做了更多真实工程化设计。

---

# 10. 你接下来如果想让我带你精读，最推荐这三个类

如果下一步你还想继续用“老师讲课模式”，我建议按这个顺序让我带你讲：

1. `DocumentUploadService`
2. `DocumentIngestService`
3. `GlobalExceptionHandler + ThrowUtils + BusinessException`

因为这三个类，最能把你这次升级的核心思想吃透。

如果你愿意，下一条你直接说：
- `先讲 DocumentUploadService`
- `先讲 DocumentIngestService`
- `先讲异常体系`

我就只盯着那个模块，继续像上次那样，一行一行给你讲。