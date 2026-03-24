package com.bloodstar.fluxragcompute.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.MilvusSearchRequest;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.ToolExecutionResult;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RagAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Qualifier("milvusSearchTool")
    private final Function<MilvusSearchRequest, ToolExecutionResult> milvusSearchTool;

    public String answer(String question) {
        ToolExecutionResult searchResult = milvusSearchTool.apply(new MilvusSearchRequest(question, 4));
        if (!searchResult.success()) {
            return "知识检索失败：" + searchResult.message();
        }
        List<?> snippets = searchResult.data() instanceof List<?> list ? list : List.of();
        if (snippets.isEmpty()) {
            return "知识库里还没有命中相关片段。你可以先通过文档导入接口把架构文档灌入向量库，再来提问。";
        }
        String prompt = """
                你是 DataPilot 的 RAG Agent。
                你必须严格依据检索到的上下文回答问题，不能凭空编造。
                如果上下文不足，就明确说明“检索到的资料不足，无法确定”。

                用户问题：
                %s

                检索上下文（JSON）：
                %s
                """.formatted(question, toJson(snippets));
        return chatClient.call(prompt);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
