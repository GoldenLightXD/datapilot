# DataPilot

DataPilot 是一个面向数据库智能运维场景的 Spring Boot 3 + Spring AI 项目，当前版本重点亮点是：
- Supervisor 多智能体架构：SupervisorAgent 通过 Function Calling 自主调度 RAG Agent 和 DBA Agent
- 真正的 Function Calling：所有 Tool 调用由大模型自主决策，业务层零 `.apply()` 调用
- 多轮对话记忆：基于 conversationId 的滑动窗口记忆，支持追问和上下文关联
- 动态数据源管理：目标数据库通过 REST API 动态增删，运行时热加载无需重启
- RAG 检索：文档上传后经 OSS + Tika + splitter + Milvus 完成知识入库
- SQL 安全沙盒：基于 Druid AST 只允许只读 SQL 执行
- MQ 异步导入：上传完成后通过 RabbitMQ 异步解析文档
- 统一异常与响应：`code + message + data` 风格

## 1. 技术栈
- JDK 21
- Maven 3.8+
- Spring Boot 3.4.13
- Spring AI 1.0.0 GA
- MyBatis-Plus 3.5.5
- Druid 1.2.21
- MySQL 8.0
- RabbitMQ
- Redis
- Milvus
- Apache Tika
- 阿里云 OSS SDK

### 为什么从 Spring AI 0.8.1 升级到 1.0.0 GA

| 对比项 | 0.8.1（旧版） | 1.0.0 GA（当前） |
|--------|--------------|------------------|
| ChatClient API | 只有 `call(String)` / `call(Prompt)` | `ChatClient.builder(chatModel)` 流式构建，支持 `.defaultFunctions()`、`.defaultAdvisors()` |
| 对话记忆 | 不存在，需手动实现 | 内置 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor`，一行配置 |
| Function Calling | 需手动拼 `OpenAiChatOptions.builder().withFunctions(...)` | `chatClient.prompt().user(msg).call().content()`，函数通过 `.defaultToolNames("beanName")` 声明式挂载 |
| Maven 仓库 | 需要 `repo.spring.io/milestone` | Maven Central 直接可用 |
| 生态状态 | 2024 年初里程碑版，已停止维护 | 2025 年 5 月 GA，稳定且持续更新 |

**结论**：1.0.0 GA 让代码更少、架构表达力更强，且作为面试项目，使用正式发布版本比使用过时的里程碑版本更合适。

## 2. 架构概览

```text
用户请求 (含 conversationId)
    |
    v
CopilotController -> CopilotService -> SupervisorAgent
    |
    |  ChatClient (挂载 memory advisor + sub-agent functions)
    |  LLM 自主决定调用哪个下属、调几次
    |
    +---> askRagAgent(question)
    |       |  内部 ChatClient (挂载 milvusSearchTool)
    |       +---> milvusSearchTool
    |
    +---> askDbaAgent(question, instanceId)
            |  内部 ChatClient (挂载 schemaReaderTool + sqlExecutorTool)
            +---> schemaReaderTool(instanceId, tableName)
            +---> sqlExecutorTool(instanceId, sql)
```

核心原则：业务层代码零 `.apply()` 调用，控制流 100% 交由大模型 Function Calling 自主调度。

## 3. 当前上传链路说明
1. 前端上传文件到 `/api/documents/upload`
2. 服务端将文件先落到本地临时文件
3. 再上传到对象存储 OSS
4. 数据库 `knowledge_document` 保存对象存储信息
5. 发送 MQ 消息，只传 `documentId + objectKey + 元数据`
6. 消费端再从 OSS 拉取文件流
7. 用 Apache Tika 解析文本
8. 用 splitter 切分后写入 Milvus 和 MySQL

## 4. 运行前准备
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

## 5. 数据库初始化
项目启动时会自动执行 `src/main/resources/sql/schema.sql`。

请先创建数据库：
```sql
CREATE DATABASE datapilot DEFAULT CHARACTER SET utf8mb4;
```

目标数据源通过 REST API 动态管理，不再需要在配置文件中写死。启动后先添加目标数据源：
```bash
curl -X POST 'http://localhost:8081/api/datasources' \
  -H 'Content-Type: application/json' \
  -d '{
    "instanceId": "db-test-01",
    "name": "测试库",
    "url": "jdbc:mysql://localhost:3306/test_db?useSSL=false",
    "username": "root",
    "password": "root"
  }'
```

## 6. 关键配置项
- `spring.datasource.*`：控制面元数据库（datapilot 自身数据）
- `spring.rabbitmq.*`：RabbitMQ
- `spring.data.redis.*`：Redis
- `spring.ai.openai.*`：ModelScope 兼容接口
- `spring.ai.vectorstore.milvus.*`：Milvus
- `datapilot.storage.oss.*`：阿里云 OSS 配置
- `datapilot.document.splitter.*`：文档切分策略
- `datapilot.query.max-limit`：SQL 默认查询上限

## 7. 启动项目
```bash
mvn clean spring-boot:run
```

或者：
```bash
mvn clean package
java -jar target/datapilot-1.0.0-SNAPSHOT.jar
```

## 8. 演示流程

### 8.1 管理目标数据源
```bash
# 添加目标数据源
curl -X POST 'http://localhost:8081/api/datasources' \
  -H 'Content-Type: application/json' \
  -d '{"instanceId":"db-test-01","name":"测试库","url":"jdbc:mysql://localhost:3306/test_db","username":"root","password":"root"}'

# 查看所有数据源（密码脱敏）
curl 'http://localhost:8081/api/datasources'

# 测试连接
curl -X POST 'http://localhost:8081/api/datasources/db-test-01/test'

# 删除数据源
curl -X DELETE 'http://localhost:8081/api/datasources/db-test-01'
```

### 8.2 上传文档
```bash
curl -X POST 'http://localhost:8081/api/documents/upload' \
  -H 'Content-Type: multipart/form-data' \
  -F 'file=@/absolute/path/to/architecture.pdf'
```

### 8.3 聊天问答（支持多轮对话）
```bash
# 第一轮：不传 conversationId，系统自动生成
curl -X POST 'http://localhost:8081/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{"message":"请介绍一下文档上传到向量库的完整链路"}'

# 返回示例：{"code":0,"data":{"conversationId":"uuid-xxx","answer":"..."}}

# 追问：传入上一轮返回的 conversationId
curl -X POST 'http://localhost:8081/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{"message":"Tika 那一步具体做了什么？","conversationId":"uuid-xxx"}'

# DBA 类问题
curl -X POST 'http://localhost:8081/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我找出 db-test-01 里最慢的 10 条 SQL"}'
```

## 9. 项目结构
```text
src/main/java/com/bloodstar/fluxragcompute
├── agent        # SupervisorAgent（Supervisor 主调度）
├── common       # ErrorCode、BaseResponse、ResultUtils
├── config       # ChatMemory、SubAgentFunction、TargetDataSourceRegistry、MQ、OSS 配置
├── controller   # CopilotController、DocumentController、DataSourceController
├── dto          # 请求响应与 Tool 载荷（含 RagAgentRequest、DbaAgentRequest 等）
├── entity       # 数据库实体（含 TargetDatasource）
├── exception    # BusinessException 与全局异常处理
├── mapper       # MyBatis-Plus Mapper
├── mq           # RabbitMQ 监听器
├── service      # 调度、安全沙盒、上传、解析、切分、入库
├── tools        # Milvus 检索、表结构读取、SQL 执行（Function Calling Bean）
└── utils        # ThrowUtils
```

## 10. 异常与响应风格
### 成功响应
```json
{
  "code": 0,
  "message": "ok",
  "data": {"conversationId": "uuid-xxx", "answer": "..."}
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

## 11. 简历可写亮点
- 基于 Spring AI 1.0.0 GA 构建 Supervisor 多智能体架构，LLM 通过 Function Calling 自主调度 RAG/DBA 子 Agent
- 实现多轮对话记忆（MessageWindowChatMemory），支持上下文追问与自我纠错
- 设计控制面与数据面物理隔离，目标数据源通过 REST API 动态管理，运行时热加载
- 所有 Tool 异常优雅处理，LLM 收到失败信号后自主纠错重试
- 基于 Druid AST 实现 SQL 安全沙盒，阻断高危写操作
- 文档导入链路：OSS + Tika + MQ 异步解析 + Milvus 向量入库
- 统一错误码、业务异常、全局异常处理与标准响应模型
