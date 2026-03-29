package com.bloodstar.fluxragcompute.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("target_datasource")
public class TargetDatasource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String instanceId;

    private String name;

    private String url;

    private String username;

    private String password;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
