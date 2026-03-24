package com.bloodstar.fluxragcompute.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("knowledge_segment")
public class KnowledgeSegment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long documentId;

    private Integer segmentIndex;

    private String content;

    private String vectorId;
}
