package com.bloodstar.fluxragcompute.service;

import com.bloodstar.fluxragcompute.agent.SupervisorAgent;
import com.bloodstar.fluxragcompute.dto.ChatResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CopilotService {

    private final SupervisorAgent supervisorAgent;

    public ChatResponse chat(String conversationId, String message) {
        if (!StringUtils.hasText(conversationId)) {
            conversationId = UUID.randomUUID().toString();
        }
        String answer = supervisorAgent.chat(conversationId, message);
        return ChatResponse.builder()
                .conversationId(conversationId)
                .answer(answer)
                .build();
    }
}
