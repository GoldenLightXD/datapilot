package com.bloodstar.fluxragcompute.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentUploadResponse {

    private Long documentId;

    private String status;

    private String provider;

    private String objectKey;

    private String fileUrl;

    private String fileName;
}
