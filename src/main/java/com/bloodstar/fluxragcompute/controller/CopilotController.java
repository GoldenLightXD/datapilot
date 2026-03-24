package com.bloodstar.fluxragcompute.controller;

import com.bloodstar.fluxragcompute.common.BaseResponse;
import com.bloodstar.fluxragcompute.common.ResultUtils;
import com.bloodstar.fluxragcompute.dto.ChatRequest;
import com.bloodstar.fluxragcompute.dto.ChatResponse;
import com.bloodstar.fluxragcompute.service.CopilotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * curl -X POST 'http://localhost:8081/api/chat' \
 *   -H 'Content-Type: application/json' \
 *   -d '{"message":"请介绍一下系统里的文档入库链路"}'
 *
 * curl -X POST 'http://localhost:8081/api/chat' \
 *   -H 'Content-Type: application/json' \
 *   -d '{"message":"帮我找出 slow_query_log 里最慢的 10 条 SQL"}'
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CopilotController {

    private final CopilotService copilotService;

    @PostMapping("/chat")
    public BaseResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResultUtils.success(copilotService.chat(request.getMessage()));
    }
}
