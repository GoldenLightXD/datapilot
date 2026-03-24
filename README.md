# DataPilot

DataPilot 是一个面向数据库智能运维场景的 Spring Boot 3 + Spring AI 项目，当前版本重点亮点是：
- Multi-Agent 协同：Router Agent / RAG Agent / DBA Agent
- RAG 检索：文档上传后经 OSS + Tika + splitter + Milvus 完成知识入库
- SQL 安全沙盒：基于 Druid AST 只允许只读 SQL 执行
- MQ 异步导入：上传完成后通过 RabbitMQ 异步解析文档
- 统一异常与响应：`code + message + data` 风格，便于前后端协作

## 1. 技术栈
- JDK 21
- Maven 3.8+
- Spring Boot 3.2.4
- Spring AI 0.8.1
- MyBatis-Plus 3.5.5
- Druid 1.2.21
- MySQL 8.0
- RabbitMQ
- Redis
- Milvus
- Apache Tika
- 阿里云 OSS SDK（当前已实现）

## 2. 当前上传链路说明
当前版本已经不再依赖“服务器本地绝对路径 JSON 入参”。

新的链路是：
1. 前端上传文件到 `/api/documents/upload`
2. 服务端将文件先落到本地临时文件
3. 再上传到对象存储 OSS
4. 数据库 `knowledge_document` 保存对象存储信息
5. 发送 MQ 消息，只传 `documentId + objectKey + 元数据`
6. 消费端再从 OSS 拉取文件流
7. 用 Apache Tika 解析文本
8. 用 splitter 切分后写入 Milvus 和 MySQL

这样更适合后续部署到服务器，也更接近分布式系统的真实做法。

## 3. 运行前准备
本项目默认依赖以下本地服务：
- MySQL: `localhost:3306`
- RabbitMQ: `localhost:5672`
- Redis: `localhost:6379`
- Milvus: `localhost:19530`

同时需要配置 AI Key 与 OSS 信息。

### macOS / Linux
```bash
export AI_KEY=your-modelscope-api-key
export OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
export OSS_REGION=cn-hangzhou
export OSS_BUCKET_NAME=your-bucket-name
export OSS_ACCESS_KEY_ID=your-access-key-id
export OSS_ACCESS_KEY_SECRET=your-access-key-secret
export OSS_BASE_PATH=datapilot/docs
```

### Windows PowerShell
```powershell
$env:AI_KEY="your-modelscope-api-key"
$env:OSS_ENDPOINT="oss-cn-hangzhou.aliyuncs.com"
$env:OSS_REGION="cn-hangzhou"
$env:OSS_BUCKET_NAME="your-bucket-name"
$env:OSS_ACCESS_KEY_ID="your-access-key-id"
$env:OSS_ACCESS_KEY_SECRET="your-access-key-secret"
$env:OSS_BASE_PATH="datapilot/docs"
```

> 说明：当前代码已实现阿里云 OSS。`application.yml` 中也预留了腾讯云 COS 的占位符配置，便于后续扩展。

## 4. 数据库初始化
项目启动时会自动执行：
- `src/main/resources/sql/schema.sql`

请先创建数据库：
```sql
CREATE DATABASE datapilot DEFAULT CHARACTER SET utf8mb4;
```

为了更方便演示 DBA 能力，你可以先插入几条慢查询样例数据：
```sql
INSERT INTO slow_query_log (id, sql_text, execution_time_ms, user, happen_time) VALUES
(1, 'select * from orders where user_id = 101', 320, 'report_user', NOW()),
(2, 'select * from orders where status = ''PAID''', 780, 'etl_user', NOW()),
(3, 'select * from customer where mobile like ''138%''', 1250, 'admin', NOW());
```

## 5. 关键配置项
- `spring.datasource.*`：MySQL
- `spring.rabbitmq.*`：RabbitMQ
- `spring.data.redis.*`：Redis
- `spring.ai.openai.*`：ModelScope 兼容接口
- `spring.ai.vectorstore.milvus.*`：Milvus
- `datapilot.storage.oss.*`：阿里云 OSS 配置
- `datapilot.storage.cos.*`：腾讯云 COS 预留配置
- `datapilot.document.splitter.*`：文档切分策略
- `datapilot.query.max-limit`：SQL 默认查询上限

## 6. 启动项目
```bash
mvn clean spring-boot:run
```

或者：
```bash
mvn clean package
java -jar target/datapilot-1.0.0-SNAPSHOT.jar
```

## 7. 演示流程
### 7.1 上传文档
```bash
curl -X POST 'http://localhost:8081/api/documents/upload' \
  -H 'Content-Type: multipart/form-data' \
  -F 'file=@/absolute/path/to/architecture.pdf'
```

上传成功后，接口会立刻返回一个受理结果，例如：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "documentId": 1947481817001,
    "status": "QUEUED",
    "provider": "aliyun",
    "objectKey": "datapilot/docs/2026/03/24/xxx-architecture.pdf",
    "fileUrl": "https://your-bucket.oss-cn-hangzhou.aliyuncs.com/...",
    "fileName": "architecture.pdf"
  }
}
```

### 7.2 问架构类问题
```bash
curl -X POST 'http://localhost:8081/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{"message":"请介绍一下文档上传到向量库的完整链路"}'
```

### 7.3 问 DBA 类问题
```bash
curl -X POST 'http://localhost:8081/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我找出 slow_query_log 里最慢的 10 条 SQL"}'
```

## 8. 项目结构
```text
src/main/java/com/bloodstar/fluxragcompute
├── agent        # Router / Rag / Dba 三个 Agent
├── common       # ErrorCode、BaseResponse、ResultUtils
├── config       # RabbitMQ、MyBatis-Plus、OSS、splitter 配置
├── controller   # 对外 HTTP 接口
├── dto          # 请求响应与文档/工具载荷
├── entity       # 数据库实体
├── exception    # BusinessException 与全局异常处理
├── mapper       # MyBatis-Plus Mapper
├── mq           # RabbitMQ 监听器
├── service      # 调度、安全沙盒、上传、解析、切分、入库
├── tools        # Milvus 检索、表结构读取、SQL 执行工具
└── utils        # ThrowUtils
```

## 9. 异常与响应风格
项目现在采用更贴近常见企业项目的统一返回风格：

### 成功响应
```json
{
  "code": 0,
  "message": "ok",
  "data": {...}
}
```

### 失败响应
```json
{
  "code": 40000,
  "message": "上传文件不能为空",
  "data": null
}
```

核心组件：
- `ErrorCode`：错误码定义
- `BusinessException`：业务异常
- `ThrowUtils`：按条件快速抛业务异常
- `ResultUtils`：统一构造成功/失败响应
- `GlobalExceptionHandler`：统一捕获异常并返回标准响应

## 10. 文档解析与切分说明
- 解析：使用 Apache Tika 统一解析 txt / md / pdf / doc / docx 等常见文档
- 切分：优先使用 Spring AI `TokenTextSplitter`
- 兜底：如果 token splitter 失败，则回退到滑动窗口切分

这使得文档链路相比 MVP 更像真实工程，而不只是简单读取文本文件。

## 11. 跨平台注意事项
- 上传接口接收的是文件本身，不再要求服务端能访问一条本地绝对路径
- 临时文件只在服务端上传到 OSS 的过程中短暂存在，上传后会删除
- macOS / Linux / Windows 都可以通过 multipart 上传调用接口
- 真正的长期存储位置是对象存储，而不是应用机器本地磁盘

## 12. 简历可写亮点
- 从零搭建 Spring AI + Milvus + RabbitMQ 的数据库智能运维 Copilot
- 设计 Multi-Agent 协同架构，完成 RAG 与 DBA 查询双链路
- 基于 Druid AST 实现 SQL 安全沙盒，阻断高危写操作
- 将文档导入链路从本地路径模式升级为 OSS + Tika + MQ 异步解析模式
- 设计统一错误码、业务异常、全局异常处理与标准响应模型
