package com.bloodstar.fluxragcompute.config;

import com.bloodstar.fluxragcompute.dto.ToolPayloads.AgentResponse;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.DbaAgentRequest;
import com.bloodstar.fluxragcompute.dto.ToolPayloads.RagAgentRequest;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

@Configuration
public class SubAgentFunctionConfig {

    private static final String RAG_SYSTEM_PROMPT = """
            你是知识库检索专家。你的任务是根据用户问题，使用 milvusSearchTool 在向量知识库中检索相关文档片段，
            然后基于检索到的内容，准确、完整地回答用户问题。
            如果检索结果为空或不相关，请如实告知用户。
            回答时引用关键知识点，不要编造文档中没有的内容。
            """;

    private static final String DBA_SYSTEM_PROMPT = """
            你是 DBA 数据库运维专家。你的任务是根据用户问题，在指定的目标数据库实例上进行查询分析。
            工作流程：
            1. 先使用 schemaReaderTool 读取相关表的结构信息
            2. 根据表结构和用户问题，生成安全的只读 SQL 查询
            3. 使用 sqlExecutorTool 执行 SQL
            4. 分析查询结果，给出专业的运维建议
            如果某步骤失败，根据错误信息调整后重试。
            注意：所有工具调用都需要传入 instanceId 参数，使用用户指定的目标数据库实例。
            """;

    @Bean("askRagAgent")
    @Description("向知识库专家提问。适用于架构设计、模块职责、规范说明、系统原理等知识性问题。参数：question - 用户问题")
    public Function<RagAgentRequest, AgentResponse> askRagAgent(ChatModel chatModel) {
        ChatClient ragClient = ChatClient.builder(chatModel)
                .defaultSystem(RAG_SYSTEM_PROMPT)
                .defaultToolNames("milvusSearchTool")
                .build();
        return request -> {
            String answer = ragClient.prompt()
                    .user(request.question())
                    .call()
                    .content();
            return new AgentResponse(answer);
        };
    }

    @Bean("askDbaAgent")
    @Description("向 DBA 数据库专家提问。适用于 SQL 查询、慢查询分析、表结构查看、数据统计等数据库运维问题。参数：question - 问题, instanceId - 目标数据库实例ID")
    public Function<DbaAgentRequest, AgentResponse> askDbaAgent(ChatModel chatModel) {
        ChatClient dbaClient = ChatClient.builder(chatModel)
                .defaultSystem(DBA_SYSTEM_PROMPT)
                .defaultToolNames("schemaReaderTool", "sqlExecutorTool")
                .build();
        return request -> {
            String userPrompt = "目标数据库实例: %s\n用户问题: %s"
                    .formatted(request.instanceId(), request.question());
            String answer = dbaClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content();
            return new AgentResponse(answer);
        };
    }
}
