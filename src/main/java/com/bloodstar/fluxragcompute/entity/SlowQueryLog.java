package com.bloodstar.fluxragcompute.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("slow_query_log")
public class SlowQueryLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String sqlText;

    private Long executionTimeMs;

    private String user;

    private LocalDateTime happenTime;
}
