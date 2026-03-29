package com.bloodstar.fluxragcompute.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DataSourceRequest {

    @NotBlank(message = "instanceId 不能为空")
    private String instanceId;

    @NotBlank(message = "name 不能为空")
    private String name;

    @NotBlank(message = "url 不能为空")
    private String url;

    @NotBlank(message = "username 不能为空")
    private String username;

    @NotBlank(message = "password 不能为空")
    private String password;
}
