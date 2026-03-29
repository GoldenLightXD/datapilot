package com.bloodstar.fluxragcompute.agent;

import com.bloodstar.fluxragcompute.config.TargetDataSourceRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class SupervisorAgent {

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是 DataPilot 数据库智能运维主管。你负责理解用户意图，自主规划步骤，并调用下属专家工具收集信息后综合回答。

            ## 可用工具
            - askRagAgent(question)：知识库专家，适用于架构设计、模块职责、规范说明、系统原理等知识性问题
            - askDbaAgent(question, instanceId)：DBA 数据库专家，适用于 SQL 查询、慢查询分析、表结构查看、数据统计等

            ## 可用的目标数据库实例 ID
            %s

            ## 工作规则
            1. 根据用户问题判断应该调用哪个专家，可以多次调用不同专家收集信息
            2. 调用 askDbaAgent 时必须指定 instanceId，如果用户未指定，请询问用户选择
            3. 综合所有收集到的信息，给出完整、专业的回答
            4. 如果问题不明确，主动向用户澄清
            """;

    private final ChatClient chatClient;

    public SupervisorAgent(ChatModel chatModel,
                           ChatMemory chatMemory,
                           TargetDataSourceRegistry dsRegistry) {
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(dsRegistry.getAvailableInstanceIds());
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultToolNames("askRagAgent", "askDbaAgent")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String chat(String conversationId, String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
