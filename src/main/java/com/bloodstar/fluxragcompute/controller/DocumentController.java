package com.bloodstar.fluxragcompute.controller;

import com.bloodstar.fluxragcompute.constant.MqConstants;
import com.bloodstar.fluxragcompute.dto.ApiResponse;
import com.bloodstar.fluxragcompute.dto.DocumentIngestMessage;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * curl -X POST 'http://localhost:8081/api/documents/ingest' \
 *   -H 'Content-Type: application/json' \
 *   -d '{"filePath":"/absolute/path/to/architecture.txt"}'
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final RabbitTemplate rabbitTemplate;

    @PostMapping("/ingest")
    public ApiResponse<Map<String, String>> ingest(@Valid @RequestBody DocumentIngestMessage message) {
        rabbitTemplate.convertAndSend(MqConstants.DOCUMENT_EXCHANGE, MqConstants.DOCUMENT_ROUTING_KEY, message);
        return ApiResponse.ok(Map.of(
                "status", "QUEUED",
                "filePath", message.getFilePath()
        ));
    }
}
