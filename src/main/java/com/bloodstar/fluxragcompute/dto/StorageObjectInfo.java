package com.bloodstar.fluxragcompute.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StorageObjectInfo {

    private String provider;

    private String bucketName;

    private String objectKey;

    private String fileUrl;

    private String contentType;
}
