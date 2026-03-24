package com.bloodstar.fluxragcompute.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bloodstar.fluxragcompute.dto.RouteDecision;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class RouterAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public RouteDecision route(String question) {
        String prompt = """
                你是 DataPilot 的 Router Agent。
                你的职责只有一个：把用户问题路由到 RAG 或 DBA。

                规则：
                1. 如果问题是在问架构设计、模块职责、规范说明、系统原理、知识库内容，返回 RAG。
                2. 如果问题是在问 SQL 查询、慢查询分析、表结构、数据库排障、统计报表，返回 DBA。
                3. 只能输出 JSON，不要输出 Markdown，不要解释。

                输出格式：
                {"target":"RAG或DBA","reason":"简短原因"}

                用户问题：
                %s
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
}
