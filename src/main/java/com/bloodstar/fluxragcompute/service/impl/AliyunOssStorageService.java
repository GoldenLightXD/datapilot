package com.bloodstar.fluxragcompute.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.bloodstar.fluxragcompute.common.ErrorCode;
import com.bloodstar.fluxragcompute.config.OssProperties;
import com.bloodstar.fluxragcompute.dto.StorageObjectInfo;
import com.bloodstar.fluxragcompute.exception.BusinessException;
import com.bloodstar.fluxragcompute.service.ObjectStorageService;
import com.bloodstar.fluxragcompute.utils.ThrowUtils;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AliyunOssStorageService implements ObjectStorageService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final OssProperties ossProperties;

    @Override
    public StorageObjectInfo upload(InputStream inputStream, String originalFilename, String contentType) {
        validateProperties();
        String objectKey = buildObjectKey(originalFilename);
        ObjectMetadata objectMetadata = new ObjectMetadata();
        if (StringUtils.hasText(contentType)) {
            objectMetadata.setContentType(contentType);
        }
        OSS ossClient = buildClient();
        try {
            ossClient.putObject(ossProperties.getBucketName(), objectKey, inputStream, objectMetadata);
            return StorageObjectInfo.builder()
                    .provider(ossProperties.getProvider())
                    .bucketName(ossProperties.getBucketName())
                    .objectKey(objectKey)
                    .fileUrl(getObjectUrl(objectKey))
                    .contentType(contentType)
                    .build();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "上传文件到对象存储失败");
        } finally {
            ossClient.shutdown();
        }
    }

    @Override
    public InputStream download(String objectKey) {
        validateProperties();
        try {
            OSS ossClient = buildClient();
            OSSObject ossObject = ossClient.getObject(ossProperties.getBucketName(), objectKey);
            return new FilterInputStream(ossObject.getObjectContent()) {
                @Override
                public void close() throws IOException {
                    super.close();
                    ossObject.close();
                    ossClient.shutdown();
                }
            };
        } catch (OSSException ex) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "对象存储中的文件不存在或不可访问");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "从对象存储下载文件失败");
        }
    }

    @Override
    public String getObjectUrl(String objectKey) {
        if (StringUtils.hasText(ossProperties.getPublicUrlPrefix())) {
            return ossProperties.getPublicUrlPrefix().replaceAll("/$", "") + "/" + objectKey;
        }
        String endpoint = ossProperties.getEndpoint();
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint.replaceAll("/$", "") + "/" + ossProperties.getBucketName() + "/" + objectKey;
        }
        return "https://" + ossProperties.getBucketName() + "." + endpoint.replaceAll("/$", "") + "/" + objectKey;
    }

    private OSS buildClient() {
        return new OSSClientBuilder().build(
                ossProperties.getEndpoint(),
                ossProperties.getAccessKeyId(),
                ossProperties.getAccessKeySecret()
        );
    }

    private String buildObjectKey(String originalFilename) {
        String safeFilename = StringUtils.hasText(originalFilename) ? originalFilename.replaceAll("\\s+", "-") : "document.bin";
        return ossProperties.getBasePath().replaceAll("/$", "")
                + "/"
                + LocalDate.now().format(DATE_FORMATTER)
                + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "-"
                + safeFilename;
    }

    private void validateProperties() {
        ThrowUtils.throwIf(!ossProperties.isEnabled(), ErrorCode.OPERATION_ERROR, "对象存储尚未启用");
        ThrowUtils.throwIf(!StringUtils.hasText(ossProperties.getEndpoint()), ErrorCode.SYSTEM_ERROR, "未配置 OSS endpoint");
        ThrowUtils.throwIf(!StringUtils.hasText(ossProperties.getBucketName()), ErrorCode.SYSTEM_ERROR, "未配置 OSS bucketName");
        ThrowUtils.throwIf(!StringUtils.hasText(ossProperties.getAccessKeyId()), ErrorCode.SYSTEM_ERROR, "未配置 OSS accessKeyId");
        ThrowUtils.throwIf(!StringUtils.hasText(ossProperties.getAccessKeySecret()), ErrorCode.SYSTEM_ERROR, "未配置 OSS accessKeySecret");
    }
}
