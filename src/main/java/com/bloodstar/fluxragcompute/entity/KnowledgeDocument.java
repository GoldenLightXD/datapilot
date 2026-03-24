package com.bloodstar.fluxragcompute.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String fileName;

    private String fileUrl;

    private String storageProvider;

    private String objectKey;

    private String contentType;

    private String status;

    private String failureReason;

    private LocalDateTime createTime;
}
