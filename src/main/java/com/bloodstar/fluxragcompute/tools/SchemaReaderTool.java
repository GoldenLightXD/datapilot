package com.bloodstar.fluxragcompute.tools;

import com.bloodstar.fluxragcompute.dto.ToolPayloads.SchemaReadRequest;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

@Configuration
public class SchemaReaderTool {

    @Bean("schemaReaderTool")
    @Description("读取 slow_query_log 等业务表的结构信息，返回字段名、类型、是否可空、主键信息与注释")
    public Function<SchemaReadRequest, ToolExecutionResult> schemaReaderTool(JdbcTemplate jdbcTemplate) {
        return request -> {
            String tableName = request == null || !StringUtils.hasText(request.tableName())
                    ? "slow_query_log"
                    : request.tableName().trim();
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
            if (rows.isEmpty()) {
                return ToolExecutionResult.failure("未找到表结构: " + tableName);
            }
            return ToolExecutionResult.success("表结构读取成功", Map.of("tableName", tableName, "columns", rows));
        };
    }
}
