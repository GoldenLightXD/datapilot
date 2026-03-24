package com.bloodstar.fluxragcompute.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.bloodstar.fluxragcompute.exception.UnsafeSqlException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SecuritySandboxService {

    private static final Set<String> EXTRA_ALLOWED_READ_ONLY_TYPES = Set.of(
            "SQLShowStatement",
            "SQLDescribeStatement",
            "SQLExplainStatement",
            "MySqlShowStatement",
            "MySqlExplainStatement",
            "MySqlDescStatement"
    );

    public void validateReadOnlySql(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new UnsafeSqlException("SQL 不能为空");
        }
        List<SQLStatement> statements = SQLUtils.parseStatements(sql, DbType.mysql);
        if (statements.size() != 1) {
            throw new UnsafeSqlException("仅允许执行单条只读 SQL");
        }
        SQLStatement statement = statements.get(0);
        if (!isReadOnlyStatement(statement)) {
            throw new UnsafeSqlException("检测到非只读 SQL，已被安全沙盒拦截");
        }
    }

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
}
