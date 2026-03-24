package com.bloodstar.fluxragcompute.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DocumentIngestMessage {

    @NotNull(message = "documentId 不能为空")
    private Long documentId;

    @NotBlank(message = "objectKey 不能为空")
    private String objectKey;

    @NotBlank(message = "storageProvider 不能为空")
    private String storageProvider;

    @NotBlank(message = "originalFilename 不能为空")
    private String originalFilename;

    private String contentType;
}
