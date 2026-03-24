package com.bloodstar.fluxragcompute.service;

import com.bloodstar.fluxragcompute.agent.DbaAgent;
import com.bloodstar.fluxragcompute.agent.RagAgent;
import com.bloodstar.fluxragcompute.agent.RouterAgent;
import com.bloodstar.fluxragcompute.dto.ChatResponse;
import com.bloodstar.fluxragcompute.dto.RouteDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CopilotService {

    private final RouterAgent routerAgent;
    private final RagAgent ragAgent;
    private final DbaAgent dbaAgent;

    public ChatResponse chat(String message) {
        RouteDecision decision = routerAgent.route(message);
        String target = decision.normalizedTarget();
        String answer = "DBA".equals(target) ? dbaAgent.answer(message) : ragAgent.answer(message);
        return ChatResponse.builder()
                .target(target)
                .reason(decision.getReason())
                .answer(answer)
                .build();
    }
}
