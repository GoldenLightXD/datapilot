package com.bloodstar.fluxragcompute.dto;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ParsedDocument {

    private String content;

    private String contentType;

    private Map<String, String> metadata;
}
