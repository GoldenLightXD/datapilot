package com.bloodstar.fluxragcompute.service;

import com.bloodstar.fluxragcompute.dto.StorageObjectInfo;
import java.io.InputStream;

public interface ObjectStorageService {

    StorageObjectInfo upload(InputStream inputStream, String originalFilename, String contentType);

    InputStream download(String objectKey);

    String getObjectUrl(String objectKey);
}
