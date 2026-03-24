package com.bloodstar.fluxragcompute.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.SchemaReadRequest;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.SqlExecutionRequest;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.SqlGenerationResult;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.ToolExecutionResult;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbaAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Qualifier("schemaReaderTool")
    private final Function<SchemaReadRequest, ToolExecutionResult> schemaReaderTool;

    @Qualifier("sqlExecutorTool")
    private final Function<SqlExecutionRequest, ToolExecutionResult> sqlExecutorTool;

    public String answer(String question) {
        ToolExecutionResult schemaResult = schemaReaderTool.apply(new SchemaReadRequest("slow_query_log"));
        if (!schemaResult.success()) {
            return "表结构读取失败：" + schemaResult.message();
        }

        SqlGenerationResult sqlPlan = generatePlan(question, schemaResult.data(), null, null);
        ToolExecutionResult executionResult = sqlExecutorTool.apply(new SqlExecutionRequest(sqlPlan.sql()));
        if (!executionResult.success()) {
            sqlPlan = generatePlan(question, schemaResult.data(), sqlPlan.sql(), executionResult.message());
            executionResult = sqlExecutorTool.apply(new SqlExecutionRequest(sqlPlan.sql()));
        }
        if (!executionResult.success()) {
            return "SQL 生成或执行失败：" + executionResult.message();
        }

        String summaryPrompt = """
                你是资深 DBA 助手。请基于下列信息给出简洁、可信、面向运维排障的分析结论。
                回答要求：
                1. 先给结论，再给证据。
                2. 可以列出 2-4 个观察点。
                3. 不要编造结果，只能依据提供的数据。

                用户问题：
                %s

                SQL 分析思路：
                %s

                实际执行 SQL：
                %s

                查询结果（JSON）：
                %s
                """.formatted(question, sqlPlan.analysis(), sqlPlan.sql(), toJson(executionResult.data()));
        return chatClient.call(summaryPrompt);
    }

    private SqlGenerationResult generatePlan(String question, Object schema, String previousSql, String errorMessage) {
        String prompt = """
                你是 DataPilot 的 DBA Agent，只能生成 MySQL 只读 SQL。
                规则：
                1. 只允许 SELECT / SHOW / DESC / EXPLAIN。
                2. 严禁 INSERT / UPDATE / DELETE / DROP / ALTER / TRUNCATE。
                3. 查询 `slow_query_log` 时，优先带上 ORDER BY 和 LIMIT。
                4. 只输出 JSON，不要输出 Markdown。
                5. JSON 格式固定为：{"analysis":"你的分析思路","sql":"生成的只读SQL"}

                用户问题：
                %s

                可用表结构：
                %s

                上一轮 SQL（如果有）：
                %s

                上一轮报错（如果有）：
                %s
                """.formatted(question, toJson(schema), StringUtils.hasText(previousSql) ? previousSql : "无", StringUtils.hasText(errorMessage) ? errorMessage : "无");
        try {
            String raw = chatClient.call(prompt);
            SqlGenerationResult result = objectMapper.readValue(extractJson(raw), SqlGenerationResult.class);
            if (result == null || !StringUtils.hasText(result.sql())) {
                return fallbackPlan(question, errorMessage);
            }
            return result;
        } catch (Exception ex) {
            log.warn("DbaAgent plan parse failed, fallback to deterministic SQL", ex);
            return fallbackPlan(question, errorMessage);
        }
    }

    private SqlGenerationResult fallbackPlan(String question, String errorMessage) {
        String analysis = "模型未稳定返回结构化 SQL，已回退到确定性慢查询分析模板";
        String lower = question == null ? "" : question.toLowerCase();
        if (lower.contains("用户") || lower.contains("user")) {
            return new SqlGenerationResult(analysis, "SELECT user, COUNT(*) AS query_count, AVG(execution_time_ms) AS avg_execution_time_ms, MAX(execution_time_ms) AS max_execution_time_ms FROM slow_query_log GROUP BY user ORDER BY max_execution_time_ms DESC LIMIT 10");
        }
        if (StringUtils.hasText(errorMessage) && errorMessage.contains("不安全")) {
            return new SqlGenerationResult(analysis, "SELECT id, sql_text, execution_time_ms, user, happen_time FROM slow_query_log ORDER BY execution_time_ms DESC LIMIT 10");
        }
        return new SqlGenerationResult(analysis, "SELECT id, sql_text, execution_time_ms, user, happen_time FROM slow_query_log ORDER BY execution_time_ms DESC LIMIT 10");
    }

    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "{}";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            trimmed = trimmed.replaceFirst("^```json", "").replaceFirst("^```", "");
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
