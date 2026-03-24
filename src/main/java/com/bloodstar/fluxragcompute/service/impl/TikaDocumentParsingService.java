package com.bloodstar.fluxragcompute.service.impl;

import com.bloodstar.fluxragcompute.common.ErrorCode;
import com.bloodstar.fluxragcompute.dto.ParsedDocument;
import com.bloodstar.fluxragcompute.exception.BusinessException;
import com.bloodstar.fluxragcompute.service.DocumentParsingService;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TikaDocumentParsingService implements DocumentParsingService {

    private final Tika tika = new Tika();

    @Override
    public ParsedDocument parse(InputStream inputStream, String filename, String contentType) {
        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            if (StringUtils.hasText(contentType)) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }
            String content = tika.parseToString(inputStream, metadata);
            if (!StringUtils.hasText(content)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "文档解析结果为空，暂不支持该文件或内容为空");
            }
            Map<String, String> metadataMap = new LinkedHashMap<>();
            for (String name : metadata.names()) {
                metadataMap.put(name, metadata.get(name));
            }
            return ParsedDocument.builder()
                    .content(content.trim())
                    .contentType(metadata.get(Metadata.CONTENT_TYPE))
                    .metadata(metadataMap)
                    .build();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "使用 Tika 解析文档失败");
        }
    }
}
