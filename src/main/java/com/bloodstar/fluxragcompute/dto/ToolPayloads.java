package com.bloodstar.fluxragcompute.dto;

import java.util.List;
import java.util.Map;

public final class ToolPayloads {

    private ToolPayloads() {
    }

    public record MilvusSearchRequest(String keyword, Integer topK) {
    }

    public record SchemaReadRequest(String instanceId, String tableName) {
    }

    public record SqlExecutionRequest(String instanceId, String sql) {
    }

    public record ToolExecutionResult(boolean success, String message, Object data) {
        public static ToolExecutionResult success(String message, Object data) {
            return new ToolExecutionResult(true, message, data);
        }

        public static ToolExecutionResult failure(String message) {
            return new ToolExecutionResult(false, message, Map.of("error", message));
        }
    }

    public record SqlGenerationResult(String analysis, String sql) {
    }

    public record RagContext(List<String> snippets) {
    }

    public record RagAgentRequest(String question) {
    }

    public record DbaAgentRequest(String question, String instanceId) {
    }

    public record AgentResponse(String answer) {
    }
}
