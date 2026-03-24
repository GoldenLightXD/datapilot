package com.bloodstar.fluxragcompute.mq;

import com.bloodstar.fluxragcompute.constant.MqConstants;
import com.bloodstar.fluxragcompute.dto.DocumentIngestMessage;
import com.bloodstar.fluxragcompute.service.DocumentIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentMqListener {

    private final DocumentIngestService documentIngestService;

    @RabbitListener(queues = MqConstants.DOCUMENT_QUEUE)
    public void onMessage(DocumentIngestMessage message) {
        log.info("Receive document ingest message. documentId={}, objectKey={}", message.getDocumentId(), message.getObjectKey());
        documentIngestService.ingest(message);
    }
}
