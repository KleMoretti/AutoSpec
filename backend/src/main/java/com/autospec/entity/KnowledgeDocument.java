package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long artifactId;

    private String artifactType;

    private Integer artifactVersion;

    private String title;

    private String status;

    private String contentHash;

    private String chunkerVersion;

    private String embeddingModel;

    private String failureMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime activatedAt;

    private LocalDateTime supersededAt;
}
