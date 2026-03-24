package com.bloodstar.fluxragcompute.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DocumentIngestMessage {

    @NotBlank(message = "filePath 不能为空")
    private String filePath;
}
