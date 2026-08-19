package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_issue")
public class ReviewIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String severity;

    private String issueType;

    private String description;

    private String suggestion;

    private String status;

    private String issueKey;

    private String artifactType;

    private String artifactPath;

    private String requirementId;

    private String evidence;

    private Long ownerUserId;

    private String resolution;

    private Long resolvedInArtifactId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
