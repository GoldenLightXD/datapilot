package com.bloodstar.fluxragcompute.service;

import com.bloodstar.fluxragcompute.common.ErrorCode;
import com.bloodstar.fluxragcompute.constant.MqConstants;
import com.bloodstar.fluxragcompute.dto.DocumentIngestMessage;
import com.bloodstar.fluxragcompute.dto.DocumentUploadResponse;
import com.bloodstar.fluxragcompute.dto.StorageObjectInfo;
import com.bloodstar.fluxragcompute.entity.KnowledgeDocument;
import com.bloodstar.fluxragcompute.mapper.KnowledgeDocumentMapper;
import com.bloodstar.fluxragcompute.utils.ThrowUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private static final String STATUS_UPLOADED = "UPLOADED";

    private final ObjectStorageService objectStorageService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final RabbitTemplate rabbitTemplate;

    public DocumentUploadResponse uploadAndDispatch(MultipartFile file) {
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        String originalFilename = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document.bin";
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("datapilot-", "-" + originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_"));
            file.transferTo(tempFile);
            StorageObjectInfo objectInfo;
            try (InputStream inputStream = Files.newInputStream(tempFile)) {
                objectInfo = objectStorageService.upload(inputStream, originalFilename, file.getContentType());
            }
            KnowledgeDocument document = new KnowledgeDocument();
            document.setFileName(originalFilename);
            document.setFileUrl(objectInfo.getFileUrl());
            document.setObjectKey(objectInfo.getObjectKey());
            document.setStorageProvider(objectInfo.getProvider());
            document.setContentType(file.getContentType());
            document.setStatus(STATUS_UPLOADED);
            document.setCreateTime(LocalDateTime.now());
            knowledgeDocumentMapper.insert(document);

            DocumentIngestMessage message = new DocumentIngestMessage();
            message.setDocumentId(document.getId());
            message.setObjectKey(objectInfo.getObjectKey());
            message.setStorageProvider(objectInfo.getProvider());
            message.setOriginalFilename(originalFilename);
            message.setContentType(file.getContentType());
            rabbitTemplate.convertAndSend(MqConstants.DOCUMENT_EXCHANGE, MqConstants.DOCUMENT_ROUTING_KEY, message);

            return DocumentUploadResponse.builder()
                    .documentId(document.getId())
                    .status("QUEUED")
                    .provider(objectInfo.getProvider())
                    .objectKey(objectInfo.getObjectKey())
                    .fileUrl(objectInfo.getFileUrl())
                    .fileName(originalFilename)
                    .build();
        } catch (IOException ex) {
            throw new com.bloodstar.fluxragcompute.exception.BusinessException(ErrorCode.SYSTEM_ERROR, "处理上传文件失败");
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
