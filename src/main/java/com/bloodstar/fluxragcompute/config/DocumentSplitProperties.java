package com.bloodstar.fluxragcompute.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "datapilot.document.splitter")
public class DocumentSplitProperties {

    private int chunkSize = 500;

    private int overlap = 100;

    private boolean useTokenSplitter = true;
}
