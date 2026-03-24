package com.bloodstar.fluxragcompute.tools;

import com.bloodstar.fluxragcompute.dto.ToolPayloads.MilvusSearchRequest;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.util.StringUtils;

@Configuration
public class MilvusSearchTool {

    @Bean("milvusSearchTool")
    @Description("根据关键词检索知识库片段，返回最相关的文档内容和元数据")
    public Function<MilvusSearchRequest, ToolExecutionResult> milvusSearchTool(VectorStore vectorStore) {
        return request -> {
            if (request == null || !StringUtils.hasText(request.keyword())) {
                return ToolExecutionResult.failure("检索关键词不能为空");
            }
            int topK = request.topK() == null ? 4 : Math.max(1, Math.min(request.topK(), 8));
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.query(request.keyword())
                            .withTopK(topK)
            );
            List<Map<String, Object>> rows = documents.stream()
                    .map(document -> Map.<String, Object>of(
                            "content", document.getContent(),
                            "metadata", document.getMetadata() == null ? Map.of() : document.getMetadata()
                    ))
                    .collect(Collectors.toList());
            return ToolExecutionResult.success("检索完成", rows);
        };
    }
}
