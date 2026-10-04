package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("requirement_baseline")
public class RequirementBaseline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String baselineId;
    private Long projectId;
    private Long workflowRunId;
    private Integer version;
    private String originalRequirement;
    private String answersJson;
    private String assumptionsJson;
    private String conflictResolutionsJson;
    private String contextConflictsJson;
    private String scopeJson;
    private String constraintsJson;
    private Long prdArtifactId;
    private Integer prdVersion;
    private String prdContentHash;
    private Long confirmedByUserId;
    private LocalDateTime confirmedAt;
    private String contentHash;
    private LocalDateTime createdAt;
}
