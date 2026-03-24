# DataPilot

DataPilot 是一个面向数据库智能运维场景的 Spring Boot 3 + Spring AI 项目，核心亮点是：
- Multi-Agent 协同：Router Agent / RAG Agent / DBA Agent
- RAG 检索：文档切片后写入 Milvus，支持架构知识问答
- SQL 安全沙盒：基于 Druid AST 只允许只读 SQL 执行
- MQ 异步导入：RabbitMQ 驱动文档解析与向量入库
- 企业常用中间件整合：MySQL / Redis / RabbitMQ / Milvus

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

## 2. 运行前准备
本项目默认依赖以下本地服务：
- MySQL: `localhost:3306`
- RabbitMQ: `localhost:5672`
- Redis: `localhost:6379`
- Milvus: `localhost:19530`

你还需要准备 AI Key：

### macOS / Linux
```bash
export AI_KEY=your-modelscope-api-key
```

### Windows PowerShell
```powershell
$env:AI_KEY="your-modelscope-api-key"
```

## 3. 数据库初始化
项目启动时会自动执行：
- `src/main/resources/sql/schema.sql`

请先创建数据库：
```sql
CREATE DATABASE datapilot DEFAULT CHARACTER SET utf8mb4;
```

可选：为了更容易演示 DBA 能力，你可以先插入几条慢查询样例数据：
```sql
INSERT INTO slow_query_log (id, sql_text, execution_time_ms, user, happen_time) VALUES
(1, 'select * from orders where user_id = 101', 320, 'report_user', NOW()),
(2, 'select * from orders where status = ''PAID''', 780, 'etl_user', NOW()),
(3, 'select * from customer where mobile like ''138%''', 1250, 'admin', NOW());
```

## 4. application.yml 里可调整的关键配置
- `spring.datasource.*`：MySQL 连接信息
- `spring.rabbitmq.*`：RabbitMQ 连接信息
- `spring.data.redis.*`：Redis 连接信息
- `spring.ai.openai.*`：ModelScope 兼容接口配置
- `spring.ai.vectorstore.milvus.*`：Milvus 配置
- `datapilot.query.max-limit`：SQL 工具默认查询上限

## 5. 启动项目
```bash
mvn clean spring-boot:run
```

或者：
```bash
mvn clean package
java -jar target/datapilot-1.0.0-SNAPSHOT.jar
```

## 6. 演示流程
### 6.1 导入文档
```bash
curl -X POST 'http://localhost:8081/api/documents/ingest' \
  -H 'Content-Type: application/json' \
  -d '{"filePath":"/absolute/path/to/architecture.txt"}'
```

### 6.2 问架构类问题
```bash
curl -X POST 'http://localhost:8081/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{"message":"请介绍一下文档导入和向量检索链路"}'
```

### 6.3 问 DBA 类问题
```bash
curl -X POST 'http://localhost:8081/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我找出 slow_query_log 里最慢的 10 条 SQL"}'
```

## 7. 项目结构
```text
src/main/java/com/bloodstar/fluxragcompute
├── agent        # Router / Rag / Dba 三个 Agent
├── config       # RabbitMQ、MyBatis-Plus 等配置
├── controller   # 对外 HTTP 接口
├── dto          # 请求响应与工具载荷
├── entity       # 数据库实体
├── exception    # 异常与统一处理
├── mapper       # MyBatis-Plus Mapper
├── mq           # RabbitMQ 监听器
├── service      # 安全沙盒、调度、文档导入
└── tools        # Milvus 检索、表结构读取、SQL 执行工具
```

## 8. 跨平台注意事项
- 控制器接收的是本地文件**绝对路径**，macOS / Linux / Windows 都可以用，只要路径写法符合目标系统即可。
- Windows 路径示例：`C:\\docs\\architecture.txt`
- Linux 路径示例：`/opt/data/architecture.txt`
- macOS 路径示例：`/Users/yourname/docs/architecture.txt`
- 代码中使用 `Path.of()` 解析路径，没有写死 macOS 特有逻辑。

## 9. 当前实现说明
- 项目已经按 Multi-Agent 思路拆分职责
- Router Agent 负责意图识别
- RAG Agent 先查向量库，再基于上下文回答
- DBA Agent 先读表结构，再生成只读 SQL，再经过安全沙盒执行
- 安全沙盒基于 Druid AST，默认拒绝写操作与多语句拼接

## 10. 简历可写亮点
- 从零搭建 Spring AI + Milvus + RabbitMQ 的智能运维 Copilot
- 设计 Multi-Agent 协同架构，完成 RAG 与 DBA 查询双链路
- 基于 Druid AST 实现 SQL 安全沙盒，阻断高危写操作
- 通过 RabbitMQ 异步文档摄入，将业务表与向量库联动
