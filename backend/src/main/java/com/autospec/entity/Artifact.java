package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("artifact")
public class Artifact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String type;

    private String title;

    private String content;

    private String format;

    private Integer version;

    private Integer lockVersion = 0;

    private String status;

    private String sourceAgent;

    private Long parentArtifactId;

    private Long workflowNodeRunId;

    private String contentHash;

    private String schemaVersion;

    private String promptKey;

    private String promptVersion;

    private String modelProvider;

    private String modelName;

    private String sourceCitationsJson;

    private String provenanceJson;

    private LocalDateTime approvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
