package com.bloodstar.fluxragcompute.tools;

import com.bloodstar.fluxragcompute.config.TargetDataSourceRegistry;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.SqlExecutionRequest;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.ToolExecutionResult;
import com.bloodstar.fluxragcompute.exception.BusinessException;
import com.bloodstar.fluxragcompute.service.SecuritySandboxService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.util.StringUtils;

@Configuration
public class SqlExecutorTool {

    @Bean("sqlExecutorTool")
    @Description("在指定数据库实例上执行只读 SQL 查询。执行前会通过 AST 安全沙盒校验，若 SQL 包含写操作或多语句拼接则拒绝执行。参数：instanceId - 目标数据库实例ID，sql - 要执行的SQL语句")
    public Function<SqlExecutionRequest, ToolExecutionResult> sqlExecutorTool(
            SecuritySandboxService securitySandboxService,
            TargetDataSourceRegistry dsRegistry,
            @Value("${datapilot.query.max-limit:100}") int maxLimit
    ) {
        return request -> {
            try {
                String instanceId = request == null ? null : request.instanceId();
                if (!StringUtils.hasText(instanceId)) {
                    return ToolExecutionResult.failure(
                            "instanceId 不能为空，可用实例: " + dsRegistry.getAvailableInstanceIds());
                }
                if (request == null || !StringUtils.hasText(request.sql())) {
                    return ToolExecutionResult.failure("SQL 不能为空");
                }
                String rawSql = request.sql().trim();
                securitySandboxService.validateReadOnlySql(rawSql);
                String executableSql = applyDefaultLimit(rawSql, maxLimit);
                List<Map<String, Object>> rows = dsRegistry.getJdbcTemplate(instanceId)
                        .queryForList(executableSql);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sql", executableSql);
                result.put("rowCount", rows.size());
                result.put("rows", rows);
                return ToolExecutionResult.success("SQL 执行成功", result);
            } catch (BusinessException ex) {
                return ToolExecutionResult.failure(ex.getMessage());
            } catch (IllegalArgumentException ex) {
                return ToolExecutionResult.failure(ex.getMessage());
            } catch (Exception ex) {
                return ToolExecutionResult.failure("SQL 执行失败: " + ex.getMessage());
            }
        };
    }

    private String applyDefaultLimit(String sql, int maxLimit) {
        String normalized = sql.toLowerCase();
        if (!normalized.startsWith("select") || normalized.contains(" limit ")) {
            return sql;
        }
        return sql + " limit " + maxLimit;
    }
}
