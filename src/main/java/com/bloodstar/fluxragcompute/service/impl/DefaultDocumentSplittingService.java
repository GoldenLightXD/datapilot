package com.bloodstar.fluxragcompute.service.impl;

import com.bloodstar.fluxragcompute.common.ErrorCode;
import com.bloodstar.fluxragcompute.config.DocumentSplitProperties;
import com.bloodstar.fluxragcompute.exception.BusinessException;
import com.bloodstar.fluxragcompute.service.DocumentSplittingService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultDocumentSplittingService implements DocumentSplittingService {

    private final DocumentSplitProperties splitProperties;

    @Override
    public List<String> split(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文档内容为空，无法切分");
        }
        List<String> chunks = splitWithTokenSplitter(content);
        if (!chunks.isEmpty()) {
            return chunks;
        }
        return splitWithSlidingWindow(content);
    }

    private List<String> splitWithTokenSplitter(String content) {
        if (!splitProperties.isUseTokenSplitter()) {
            return List.of();
        }
        try {
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> documents = splitter.apply(List.of(new Document(content)));
            List<String> chunks = new ArrayList<>();
            for (Document document : documents) {
                if (StringUtils.hasText(document.getContent())) {
                    chunks.add(document.getContent().trim());
                }
            }
            return chunks;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> splitWithSlidingWindow(String content) {
        int windowSize = splitProperties.getChunkSize();
        int overlap = splitProperties.getOverlap();
        if (windowSize <= overlap) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "切分配置非法：chunkSize 必须大于 overlap");
        }
        if (content.length() <= windowSize) {
            return List.of(content.trim());
        }
        int step = windowSize - overlap;
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < content.length(); start += step) {
            int end = Math.min(content.length(), start + windowSize);
            String chunk = content.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            if (end >= content.length()) {
                break;
            }
        }
        if (chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文档切分结果为空");
        }
        return chunks;
    }
}
