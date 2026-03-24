package com.bloodstar.fluxragcompute.service;

import com.bloodstar.fluxragcompute.common.ErrorCode;
import com.bloodstar.fluxragcompute.dto.DocumentIngestMessage;
import com.bloodstar.fluxragcompute.dto.ParsedDocument;
import com.bloodstar.fluxragcompute.entity.KnowledgeDocument;
import com.bloodstar.fluxragcompute.entity.KnowledgeSegment;
import com.bloodstar.fluxragcompute.exception.BusinessException;
import com.bloodstar.fluxragcompute.mapper.KnowledgeDocumentMapper;
import com.bloodstar.fluxragcompute.mapper.KnowledgeSegmentMapper;
import com.bloodstar.fluxragcompute.utils.ThrowUtils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeSegmentMapper knowledgeSegmentMapper;
    private final ObjectStorageService objectStorageService;
    private final DocumentParsingService documentParsingService;
    private final DocumentSplittingService documentSplittingService;
    private final VectorStore vectorStore;

    @Transactional(rollbackFor = Exception.class)
    public Long ingest(DocumentIngestMessage message) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(message.getDocumentId());
        ThrowUtils.throwIf(document == null, ErrorCode.NOT_FOUND_ERROR, "文档记录不存在，documentId=" + message.getDocumentId());
        try (InputStream inputStream = objectStorageService.download(message.getObjectKey())) {
            updateDocumentStatus(document.getId(), STATUS_PROCESSING, null);
            ParsedDocument parsedDocument = documentParsingService.parse(inputStream, message.getOriginalFilename(), message.getContentType());
            List<String> segments = documentSplittingService.split(parsedDocument.getContent());
            ThrowUtils.throwIf(segments.isEmpty(), ErrorCode.OPERATION_ERROR, "文档切分结果为空");

            List<Document> vectorDocuments = new ArrayList<>(segments.size());
            List<KnowledgeSegment> segmentEntities = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                String segmentContent = segments.get(i);
                String vectorId = document.getId() + "-" + (i + 1);
                vectorDocuments.add(new Document(segmentContent, Map.of(
                        "documentId", document.getId(),
                        "vectorId", vectorId,
                        "segmentIndex", i,
                        "fileName", document.getFileName(),
                        "objectKey", document.getObjectKey()
                )));
                KnowledgeSegment segment = new KnowledgeSegment();
                segment.setDocumentId(document.getId());
                segment.setSegmentIndex(i);
                segment.setContent(segmentContent);
                segment.setVectorId(vectorId);
                segmentEntities.add(segment);
            }
            vectorStore.add(vectorDocuments);
            for (KnowledgeSegment segmentEntity : segmentEntities) {
                knowledgeSegmentMapper.insert(segmentEntity);
            }
            updateDocumentStatus(document.getId(), STATUS_COMPLETED, null);
            return document.getId();
        } catch (BusinessException ex) {
            markFailed(document.getId(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            markFailed(document.getId(), "文档入库失败");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文档入库失败，请检查日志");
        }
    }

    private void updateDocumentStatus(Long documentId, String status, String failureReason) {
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(documentId);
        update.setStatus(status);
        update.setFailureReason(failureReason);
        knowledgeDocumentMapper.updateById(update);
    }

    private void markFailed(Long documentId, String reason) {
        log.error("Document ingest failed. documentId={}, reason={}", documentId, reason);
        updateDocumentStatus(documentId, STATUS_FAILED, StringUtils.hasText(reason) ? reason : "未知错误");
    }
}
