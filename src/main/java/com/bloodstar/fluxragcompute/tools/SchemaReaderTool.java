package com.bloodstar.fluxragcompute.tools;

import com.bloodstar.fluxragcompute.config.TargetDataSourceRegistry;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.SchemaReadRequest;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.util.StringUtils;

@Configuration
public class SchemaReaderTool {

    @Bean("schemaReaderTool")
    @Description("读取指定数据库实例中某张表的结构信息，返回字段名、类型、是否可空、主键信息与注释。参数：instanceId - 目标数据库实例ID，tableName - 表名(可选，默认slow_query_log)")
    public Function<SchemaReadRequest, ToolExecutionResult> schemaReaderTool(
            TargetDataSourceRegistry dsRegistry) {
        return request -> {
            try {
                String instanceId = request == null ? null : request.instanceId();
                if (!StringUtils.hasText(instanceId)) {
                    return ToolExecutionResult.failure(
                            "instanceId 不能为空，可用实例: " + dsRegistry.getAvailableInstanceIds());
                }
                String tableName = request.tableName();
                if (!StringUtils.hasText(tableName)) {
                    tableName = "slow_query_log";
                }
                List<Map<String, Object>> rows = dsRegistry.getJdbcTemplate(instanceId).queryForList("""
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
                return ToolExecutionResult.success("表结构读取成功",
                        Map.of("tableName", tableName, "columns", rows));
            } catch (IllegalArgumentException ex) {
                return ToolExecutionResult.failure(ex.getMessage());
            } catch (Exception ex) {
                return ToolExecutionResult.failure("读取表结构失败: " + ex.getMessage());
            }
        };
    }
}
