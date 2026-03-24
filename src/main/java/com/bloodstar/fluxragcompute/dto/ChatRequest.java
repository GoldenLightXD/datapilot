package com.bloodstar.fluxragcompute.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "message 不能为空")
    private String message;
}
