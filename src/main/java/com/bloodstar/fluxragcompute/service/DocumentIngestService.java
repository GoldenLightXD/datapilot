package com.bloodstar.fluxragcompute.service;

import com.bloodstar.fluxragcompute.dto.DocumentIngestMessage;
import com.bloodstar.fluxragcompute.entity.KnowledgeDocument;
import com.bloodstar.fluxragcompute.entity.KnowledgeSegment;
import com.bloodstar.fluxragcompute.mapper.KnowledgeDocumentMapper;
import com.bloodstar.fluxragcompute.mapper.KnowledgeSegmentMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
    private final VectorStore vectorStore;

    @Transactional(rollbackFor = Exception.class)
    public Long ingest(DocumentIngestMessage message) {
        Path path = Path.of(message.getFilePath());
        KnowledgeDocument document = createDocument(path);
        try {
            if (!Files.exists(path)) {
                markFailed(document.getId(), "文件不存在");
                throw new IllegalStateException("文件不存在: " + path);
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                markFailed(document.getId(), "文件为空");
                throw new IllegalStateException("文件为空: " + path);
            }
            List<String> segments = splitText(content, 500, 100);
            List<Document> vectorDocuments = new ArrayList<>(segments.size());
            List<KnowledgeSegment> segmentEntities = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                String segmentContent = segments.get(i);
                String vectorId = document.getId() + "-" + (i + 1);
                vectorDocuments.add(new Document(segmentContent, Map.of(
                        "documentId", document.getId(),
                        "vectorId", vectorId,
                        "segmentIndex", i,
                        "fileName", document.getFileName()
                )));
                KnowledgeSegment segment = new KnowledgeSegment();
                segment.setDocumentId(document.getId());
                segment.setContent(segmentContent);
                segment.setVectorId(vectorId);
                segmentEntities.add(segment);
            }
            vectorStore.add(vectorDocuments);
            for (KnowledgeSegment segmentEntity : segmentEntities) {
                knowledgeSegmentMapper.insert(segmentEntity);
            }
            updateStatus(document.getId(), STATUS_COMPLETED);
            return document.getId();
        } catch (IOException ex) {
            markFailed(document.getId(), "读取文件失败");
            throw new IllegalStateException("读取文件失败: " + path, ex);
        } catch (Exception ex) {
            markFailed(document.getId(), ex.getMessage());
            throw ex;
        }
    }

    public List<String> splitText(String content, int windowSize, int overlap) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        if (windowSize <= overlap) {
            throw new IllegalArgumentException("windowSize 必须大于 overlap");
        }
        if (content.length() <= windowSize) {
            return List.of(content.trim());
        }
        List<String> segments = new ArrayList<>();
        int step = windowSize - overlap;
        for (int start = 0; start < content.length(); start += step) {
            int end = Math.min(content.length(), start + windowSize);
            String segment = content.substring(start, end).trim();
            if (StringUtils.hasText(segment)) {
                segments.add(segment);
            }
            if (end >= content.length()) {
                break;
            }
        }
        return segments.isEmpty() ? List.of(content.trim()) : segments;
    }

    private KnowledgeDocument createDocument(Path path) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setFileName(path.getFileName() == null ? path.toString() : path.getFileName().toString());
        document.setFileUrl(path.toAbsolutePath().toString());
        document.setStatus(STATUS_PROCESSING);
        document.setCreateTime(LocalDateTime.now());
        knowledgeDocumentMapper.insert(document);
        return document;
    }

    private void updateStatus(Long documentId, String status) {
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(documentId);
        update.setStatus(status);
        knowledgeDocumentMapper.updateById(update);
    }

    private void markFailed(Long documentId, String reason) {
        log.error("Document ingest failed. documentId={}, reason={}", documentId, reason);
        updateStatus(documentId, STATUS_FAILED);
    }
}
