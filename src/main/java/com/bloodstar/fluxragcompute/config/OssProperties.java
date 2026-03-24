package com.bloodstar.fluxragcompute.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "datapilot.storage.oss")
public class OssProperties {

    private boolean enabled = true;

    private String provider = "aliyun";

    private String endpoint;

    private String region;

    private String bucketName;

    private String accessKeyId;

    private String accessKeySecret;

    private String basePath = "datapilot/docs";

    private String publicUrlPrefix;
}
